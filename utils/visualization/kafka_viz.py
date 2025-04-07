import graphviz
import yaml
from pathlib import Path


def parse_kafka_properties(server_props_path):
    """Parse Kafka server properties file"""
    config = {}
    with open(server_props_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                if '=' in line:
                    key, value = line.split('=', 1)
                    config[key.strip()] = value.strip()
    return config

def parse_compose_config(compose_path):
    """Parse docker-compose file"""
    with open(compose_path, 'r') as f:
        return yaml.safe_load(f)

def build_kafka_diagram(compose_config, server_configs):
    """Generate Kafka architecture diagram with KRaft mode"""
    dot = graphviz.Digraph('KafkaArchitecture',
        format='png',
        graph_attr={
            'rankdir': 'TB',
            'fontname': 'Helvetica',
            'fontsize': '14',
            'splines': 'ortho',
            'nodesep': '0.4',
            'ranksep': '0.5',
            'compound': 'true'
        },
        node_attr={
            'fontname': 'Helvetica',
            'fontsize': '12',
            'style': 'filled'
        },
        edge_attr={
            'fontsize': '11'
        })
    
    # ===== KAFKA CLUSTER =====
    with dot.subgraph(name='cluster_kafka') as c:
        c.attr(label='Kafka Cluster (KRaft Mode)',
              style='filled,rounded',
              color='lightgrey',
              fontsize='14')
        
        # Kafka Brokers
        broker_count = len(server_configs)
        for broker_id, config in server_configs.items():
            # Format listeners vertically
            listeners = config.get('listeners', 'PLAINTEXT://:9092').split(',')
            listeners_formatted = '\n'.join([f"• {l.strip()}" for l in listeners])
            
            # Get controller quorum voters if exists
            controller_quorum = config.get('controller.quorum.voters', '')
            if controller_quorum:
                controller_quorum = f"\nQuorum Voters:\n{controller_quorum}"
            
            c.node(f'broker{broker_id}',
                  label=f'Broker {broker_id}\n\n'
                        f"Node ID: {config.get('node.id', broker_id)}\n"
                        f"Listeners:\n{listeners_formatted}\n"
                        f"Log Dir: {config.get('log.dirs', '/tmp/kafka-logs')}\n"
                        f"Role: {config.get('process.roles', 'broker,controller')}"
                        f"{controller_quorum}",
                  shape='box',
                  fillcolor='#ffe6e6',
                  fontsize='12')
    
    # ===== CONTROLLER COMMUNICATION =====
    if len(server_configs) > 1:
        with dot.subgraph(name='cluster_controller') as c:
            c.attr(label='Controller Communication',
                  style='dashed,rounded',
                  color='#e6e6ff',
                  fontsize='14')
            
            for broker_id in server_configs:
                if 'controller' in server_configs[broker_id].get('process.roles', ''):
                    c.node(f'controller{broker_id}',
                          label=f'Controller\n(Broker {broker_id})',
                          shape='box',
                          fillcolor='#e6f3ff',
                          fontsize='12')
    
    # ===== EXTERNAL COMPONENTS =====
    with dot.subgraph(name='cluster_external') as c:
        c.attr(label='External Components',
              style='filled,rounded',
              color='#f0f0f0',
              fontsize='14')
        
        # Kafka Connect
        if 'connect' in compose_config['services']:
            connect_ports = compose_config['services']['connect'].get('ports', [])
            connect_port = next((p.split(':')[1] for p in connect_ports if '8083' in p), '8083')
            
            c.node('connect',
                  label='Kafka Connect\n\n'
                        f"REST Port: {connect_port}",
                  shape='box',
                  fillcolor='#e6ffe6',
                  fontsize='12')
        
        # Schema Registry
        if 'schema-registry' in compose_config['services']:
            sr_ports = compose_config['services']['schema-registry'].get('ports', [])
            sr_port = next((p.split(':')[1] for p in sr_ports if '8081' in p), '8081')
            
            c.node('schema_registry',
                  label='Schema Registry\n\n'
                        f"Port: {sr_port}",
                  shape='box',
                  fillcolor='#fff0e6',
                  fontsize='12')
    
    # ===== CONNECTIONS =====
    # Brokers to each other (KRaft mode)
    if len(server_configs) > 1:
        for i in range(1, len(server_configs)):
            dot.edge(f'broker{i-1}', f'broker{i}',
                    label='Metadata Replication\n(KRaft Protocol)',
                    dir='both',
                    color='green',
                    fontsize='12')
    
    # Connect to Brokers
    if 'connect' in compose_config['services']:
        dot.edge('connect', 'broker1',
                label='Produce/Consume',
                style='dashed',
                color='purple',
                fontsize='12')
    
    # Schema Registry to Brokers
    if 'schema-registry' in compose_config['services']:
        dot.edge('schema_registry', 'broker1',
                label='Schema Storage',
                style='dashed',
                color='orange',
                fontsize='12')
    
    # Controller connections
    if len(server_configs) > 1:
        for broker_id in server_configs:
            if 'controller' in server_configs[broker_id].get('process.roles', ''):
                dot.edge(f'controller{broker_id}', f'broker{broker_id}',
                        style='invis')  # Align controller with broker
                for other_id in server_configs:
                    if other_id != broker_id:
                        dot.edge(f'controller{broker_id}', f'broker{other_id}',
                                label='Metadata\nPropagation',
                                style='dashed',
                                color='blue',
                                fontsize='10')
    
    # ===== CONFIGURATION DETAILS =====
    with dot.subgraph(name='cluster_config') as c:
        c.attr(label='Configuration Details',
              style='filled,rounded',
              color='#f0f0f0',
              fontsize='14')
        
        # KRaft specific config
        broker_id, config = next(iter(server_configs.items()))
        c.node('kraft_config',
              label='KRaft Configuration\n\n'
                    f"Process Roles: {config.get('process.roles', 'broker,controller')}\n"
                    f"Controller Quorum: {config.get('controller.quorum.voters', '')}\n"
                    f"Metadata Log Dir: {config.get('metadata.log.dir', '')}",
              shape='note',
              fontsize='12')
        
        # Network config from compose
        c.node('network_config',
              label='Network Configuration\n\n'
                    f"Driver: {compose_config.get('networks', {}).get('kafka-net', {}).get('driver', 'bridge')}\n"
                    f"Broker Ports: {[c.get('listeners', 'PLAINTEXT://:9092').split(',')[0].split(':')[-1] for c in server_configs.values()]}",
              shape='note',
              fontsize='12')
    
    dot.edge('broker1', 'kraft_config', style='dashed', color='gray50')
    dot.edge('broker1', 'network_config', style='dashed', color='gray50')
    
    return dot

if __name__ == "__main__":
    # Path configuration
    base_dir = Path(__file__).parent
    compose_path = base_dir / "kafka" / "docker-compose.kafka.yml"
    server1_path = base_dir / "kafka" / "server-1.properties"
    server2_path = base_dir / "kafka" / "server-2.properties"
    
    # Parse configurations
    compose_config = parse_compose_config(compose_path)
    
    server_configs = {
        1: parse_kafka_properties(server1_path),
        2: parse_kafka_properties(server2_path)
    }
    
    # Generate diagram
    diagram = build_kafka_diagram(compose_config, server_configs)
    
    # Render and display
    try:
        output_path = diagram.render('kafka_diagram', 
                                   view=True, 
                                   cleanup=True,
                                   format='png',
                                   engine='dot')
        print(f"Successfully generated Kafka diagram: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
