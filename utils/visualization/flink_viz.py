import graphviz
import re
import yaml
from pathlib import Path


def extract_services_from_script(script_path):
    """Parse services from bash script"""
    services = set()
    with open(script_path, 'r') as f:
        content = f.read()
        
        # Find all docker compose up commands
        deployments = re.findall(r'docker compose .*? up -d ([\w-]+)', content)
        services.update(deployments)
        
    return sorted(services)

def extract_services_from_compose(compose_path):
    """Extract services from docker-compose file"""
    with open(compose_path, 'r') as f:
        compose_data = yaml.safe_load(f)
    
    services = set()
    if 'services' in compose_data:
        services.update(compose_data['services'].keys())
    
    return sorted(services)

def extract_relationships(compose_path):
    """Extract service relationships from docker-compose"""
    relationships = []
    with open(compose_path, 'r') as f:
        compose_data = yaml.safe_load(f)
    
    if 'services' in compose_data:
        for service, config in compose_data['services'].items():
            if 'depends_on' in config:
                for dependency in config['depends_on']:
                    relationships.append((service, dependency))
    
    # Add implicit Flink relationships
    if 'taskmanager' in compose_data.get('services', {}):
        relationships.append(('taskmanager', 'jobmanager'))
    if 'flink-job' in compose_data.get('services', {}):
        relationships.append(('flink-job', 'jobmanager'))
        relationships.append(('flink-job', 'kafka-1'))
        relationships.append(('flink-job', 'kafka-2'))
    
    return relationships

def build_diagram(services, relationships):
    """Generate Graphviz diagram for Flink architecture"""
    dot = graphviz.Digraph('FlinkArchitecture', 
        format='png',
        graph_attr={
            'rankdir': 'TB',
            'fontname': 'Helvetica',
            'splines': 'ortho'
        },
        node_attr={
            'shape': 'box',
            'fontname': 'Helvetica',
            'style': 'filled'
        })
    
    # Cluster for HDFS
    with dot.subgraph(name='cluster_hdfs') as c:
        c.attr(label='HDFS Cluster', style='filled', color='lightgrey')
        if 'namenode' in services:
            c.node('namenode', fillcolor='#e6f3ff', shape='cylinder')
        if 'datanode' in services:
            c.node('datanode', fillcolor='#e6ffe6', shape='cylinder')

    # Cluster for Kafka
    with dot.subgraph(name='cluster_kafka') as c:
        c.attr(label='Kafka Cluster', style='filled', color='#ffe6e6')
        if 'kafka-1' in services:
            c.node('kafka-1', shape='box3d', fillcolor='#ffb3b3')
        if 'kafka-2' in services:
            c.node('kafka-2', shape='box3d', fillcolor='#ffb3b3')

    # Cluster for Flink
    with dot.subgraph(name='cluster_flink') as c:
        c.attr(label='Flink Cluster', style='filled', color='#e6f7ff')
        if 'jobmanager' in services:
            c.node('jobmanager', shape='doublecircle', fillcolor='#99ccff')
        if 'taskmanager' in services:
            c.node('taskmanager', shape='box3d', fillcolor='#cce6ff')

    # Flink Job
    if 'flink-job' in services:
        dot.node('flink-job', shape='component', fillcolor='#99ff99')

    # Data Flow
    if 'kafka-1' in services and 'flink-job' in services:
        dot.edge('kafka-1', 'flink-job', label='Consumes from', style='dashed', color='purple')
    
    if 'kafka-2' in services and 'flink-job' in services:
        dot.edge('kafka-2', 'flink-job', label='Consumes from', style='dashed', color='purple')
    
    if 'flink-job' in services and 'namenode' in services:
        dot.edge('flink-job', 'namenode', label='Writes to', style='dashed', color='blue')
    
    if 'jobmanager' in services and 'taskmanager' in services:
        dot.edge('jobmanager', 'taskmanager', label='Manages', color='orange')
    
    if 'flink-job' in services and 'jobmanager' in services:
        dot.edge('flink-job', 'jobmanager', label='Submits to', color='red')

    # Add relationships from docker-compose
    for source, target in relationships:
        if source in services and target in services:
            dot.edge(target, source, style='dotted')

    # Add ports information
    port_info = {
        'namenode': '9870(UI)\n8020(HDFS)',
        'datanode': '9864(UI)',
        'kafka-1': '9092',
        'kafka-2': '9095',
        'jobmanager': '8081(UI)\n6123(RPC)',
        'taskmanager': '6125(Data)',
        'flink-job': 'N/A'
    }
    
    for service, ports in port_info.items():
        if service in services:
            dot.node(f'{service}_ports', 
                    label=ports,
                    shape='note',
                    fillcolor='#f0f0f0',
                    fontsize='10')
            dot.edge(service, f'{service}_ports', 
                    style='dashed',
                    arrowhead='none',
                    color='gray50')

    return dot

if __name__ == "__main__":
    # Paths relative to the script location
    base_dir = Path(__file__).parent
    script_path = base_dir / "flink" / "start-flink.sh"
    compose_path = base_dir / "flink" / "docker-compose.flink.yml"
    
    # Verify files exist
    if not script_path.exists():
        print(f"Error: Script not found at {script_path}")
        exit(1)
    if not compose_path.exists():
        print(f"Error: Compose file not found at {compose_path}")
        exit(1)
    
    # Extract data from both sources
    try:
        script_services = extract_services_from_script(script_path)
        compose_services = extract_services_from_compose(compose_path)
        relationships = extract_relationships(compose_path)
    except Exception as e:
        print(f"Error processing files: {e}")
        exit(1)
    
    # Combine services (removing duplicates)
    all_services = sorted(set(script_services + compose_services))
    
    # Generate diagram
    diagram = build_diagram(all_services, relationships)
    
    # Render and display
    try:
        output_path = diagram.render('flink_diagram', view=True, cleanup=True)
        print(f"Successfully generated: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
