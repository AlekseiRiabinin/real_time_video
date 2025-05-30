import graphviz
import re
import yaml
from pathlib import Path


def extract_services_from_script(script_path):
    """Parse docker-compose services from bash script"""
    services = set()
    with open(script_path, 'r') as f:
        content = f.read()
        
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
    """Extract service relationships from docker-compose depends_on"""
    relationships = []
    with open(compose_path, 'r') as f:
        compose_data = yaml.safe_load(f)
    
    if 'services' in compose_data:
        for service, config in compose_data['services'].items():
            if 'depends_on' in config:
                for dependency in config['depends_on']:
                    relationships.append((service, dependency))
    
    return relationships

def build_diagram(services, relationships):
    """Generate compact vertical diagram without topic nodes"""
    dot = graphviz.Digraph('StreamArchitecture', 
        format='png',
        graph_attr={
            'rankdir': 'TB',
            'fontname': 'Helvetica',
            'splines': 'ortho',
            'nodesep': '0.3',
            'ranksep': '0.4',
            'newrank': 'true'
        },
        node_attr={
            'fontname': 'Helvetica',
            'style': 'filled'
        })
    
    # Cluster for HDFS (top)
    with dot.subgraph(name='cluster_hdfs') as c:
        c.attr(label='HDFS Cluster', 
              style='filled,rounded', 
              color='lightgrey',
              fontsize='12')
        if 'namenode' in services:
            c.node('namenode', shape='cylinder', fillcolor='#e6f3ff')
        if 'datanode' in services:
            c.node('datanode', shape='cylinder', fillcolor='#e6ffe6')

    # Cluster for Kafka (middle) - simplified without topics
    with dot.subgraph(name='cluster_kafka') as c:
        c.attr(label='Kafka Cluster', 
              style='filled,rounded', 
              color='#ffe6e6',
              fontsize='12')
        if 'kafka-1' in services:
            c.node('kafka-1', shape='box3d', fillcolor='#ffb3b3')
        if 'kafka-2' in services:
            c.node('kafka-2', shape='box3d', fillcolor='#ffb3b3')

    # Kafka Service
    if 'kafka-service' in services:
        dot.node('kafka-service', shape='doublecircle', fillcolor='#99ff99')

    # Monitoring (right side)
    with dot.subgraph(name='cluster_monitoring') as c:
        c.attr(label='Monitoring', 
              style='filled,rounded', 
              color='#e6f3ff',
              fontsize='12')
        if 'prometheus' in services:
            c.node('prometheus', shape='box3d', fillcolor='#cce6ff')
        if 'grafana' in services:
            c.node('grafana', shape='box3d', fillcolor='#99ccff')

    # Combined Producer Clients (bottom)
    producer_clients = [client for client in ['kafka-client', 'akka-client', 'cats-client',
                                            'fs2-client', 'zio-client'] 
                       if client in services]
    
    if producer_clients:
        with dot.subgraph(name='cluster_producers') as c:
            c.attr(label='Producer Clients',
                  style='filled,rounded',
                  color='#f0fff0',
                  fontsize='12')
            
            producer_label = '''<
                <table border="0" cellborder="1" cellspacing="0" cellpadding="4">
                    <tr><td bgcolor="#ccffcc" colspan="2"><b>Producer Clients</b></td></tr>
                    {rows}
                </table>
            >'''.format(
                rows='\n'.join([f'<tr><td align="left" bgcolor="#e6ffe6">{client}</td></tr>' 
                              for client in producer_clients]))
            
            c.node('producers', 
                  shape='plaintext', 
                  margin='0', 
                  label=producer_label)

    # Add relationships from docker-compose
    for source, target in relationships:
        if source in services and target in services:
            if source not in producer_clients and target not in producer_clients:
                dot.edge(target, source, style='dotted')

    # Producer connections (implicit topics)
    if producer_clients and 'kafka-1' in services:
        dot.edge('producers', 'kafka-1', 
                label='produces to\nvideo-stream',
                style='dashed', 
                color='green')
        dot.edge('kafka-1', 'producers', 
                label='bootstrap', 
                style='dashed', 
                color='gray50')
        dot.edge('kafka-2', 'producers', 
                label='bootstrap', 
                style='dashed', 
                color='gray50')

    # Kafka service relationships
    if 'kafka-service' in services:
        dot.edge('kafka-service', 'kafka-1', 
                label='manages', 
                color='orange')
        dot.edge('kafka-service', 'kafka-2', 
                label='manages', 
                color='orange')
        if 'namenode' in services:
            dot.edge('kafka-service', 'namenode', 
                    label='writes to', 
                    style='dashed', 
                    color='blue')

    # Monitoring relationships
    if 'prometheus' in services and 'grafana' in services:
        dot.edge('prometheus', 'grafana', 
                label='feeds', 
                color='blue')

    # Add ports information
    port_info = {
        'namenode': '9870\n8020',
        'datanode': '9864',
        'kafka-1': '9092',
        'kafka-2': '9095',
        'kafka-service': '9091',
        'prometheus': '9090',
        'grafana': '3000',
        'producers': '\n'.join([f'{c.split("-")[0]}:{p}' for c, p in 
                              zip(['kafka-client', 'akka-client', 'cats-client',
                                   'fs2-client', 'zio-client'],
                                  ['9080', '9081', '9082', '9083', '9084'])
                     if c in producer_clients])
    }
    
    for service, ports in port_info.items():
        if service in services or (service == 'producers' and producer_clients):
            dot.node(f'{service}_ports', 
                    label=ports,
                    shape='note',
                    fillcolor='#f0f0f0',
                    fontsize='9')
            dot.edge(service if service != 'producers' else 'producers', 
                    f'{service}_ports', 
                    style='dashed',
                    arrowhead='none',
                    color='gray50')

    return dot

if __name__ == "__main__":
    base_dir = Path(__file__).parent
    script_path = base_dir / "stream" / "start-stream.sh"
    compose_path = base_dir / "stream" / "docker-compose.stream.yml"
    
    if not script_path.exists():
        print(f"Error: Script not found at {script_path}")
        exit(1)
    if not compose_path.exists():
        print(f"Error: Compose file not found at {compose_path}")
        exit(1)
    
    try:
        script_services = extract_services_from_script(script_path)
        compose_services = extract_services_from_compose(compose_path)
        relationships = extract_relationships(compose_path)
    except Exception as e:
        print(f"Error processing files: {e}")
        exit(1)
    
    all_services = sorted(set(script_services + compose_services))
    diagram = build_diagram(all_services, relationships)
    
    try:
        output_path = diagram.render('stream_diagram', view=True, cleanup=True)
        print(f"Successfully generated compact diagram: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
