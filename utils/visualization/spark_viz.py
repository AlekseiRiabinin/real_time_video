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
    
    # Add implicit Spark relationships
    if 'spark-worker' in compose_data.get('services', {}):
        relationships.append(('spark-worker', 'spark-master'))
    if 'spark-job' in compose_data.get('services', {}):
        relationships.append(('spark-job', 'namenode'))
        relationships.append(('spark-job', 'spark-master'))
    
    return relationships

def build_diagram(services, relationships):
    """Generate Graphviz diagram for Spark architecture"""
    dot = graphviz.Digraph('SparkArchitecture', 
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

    # Cluster for Spark Core
    with dot.subgraph(name='cluster_spark_core') as c:
        c.attr(label='Spark Cluster', style='filled', color='#fff7e6')
        if 'spark-master' in services:
            c.node('spark-master', shape='doublecircle', fillcolor='#ffcc99')
        if 'spark-worker' in services:
            c.node('spark-worker', shape='box3d', fillcolor='#ffdd99')

    # Spark Job
    if 'spark-job' in services:
        dot.node('spark-job', shape='component', fillcolor='#99ccff')

    # Data Flow
    if 'namenode' in services and 'spark-job' in services:
        dot.edge('namenode', 'spark-job', label='Reads from', style='dashed', color='blue')
    
    if 'spark-master' in services and 'spark-worker' in services:
        dot.edge('spark-master', 'spark-worker', label='Manages', color='orange')
    
    if 'spark-job' in services and 'spark-master' in services:
        dot.edge('spark-job', 'spark-master', label='Submits to', color='red')

    # Add relationships from docker-compose
    for source, target in relationships:
        if source in services and target in services:
            dot.edge(target, source, style='dotted')

    # Add ports information
    port_info = {
        'namenode': '9870(UI)\n8020(HDFS)',
        'datanode': '9864(UI)',
        'spark-master': '7077(Cluster)\n8080(UI)',
        'spark-worker': 'Random',
        'spark-job': 'N/A'
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
    script_path = base_dir / "spark" / "start-spark.sh"
    compose_path = base_dir / "spark" / "docker-compose.spark.yml"
    
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
        output_path = diagram.render('spark_diagram', view=True, cleanup=True)
        print(f"Successfully generated: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
