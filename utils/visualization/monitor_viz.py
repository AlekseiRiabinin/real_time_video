import graphviz
import yaml
from pathlib import Path


def parse_prometheus_config(prometheus_path):
    """Parse Prometheus configuration file"""
    with open(prometheus_path, 'r') as f:
        return yaml.safe_load(f)

def parse_compose_config(compose_path):
    """Parse docker-compose file"""
    with open(compose_path, 'r') as f:
        return yaml.safe_load(f)

def build_monitoring_diagram(compose_config, prometheus_config):
    """Generate monitoring architecture diagram"""
    dot = graphviz.Digraph('MonitoringArchitecture',
        format='png',
        graph_attr={
            'rankdir': 'TB',
            'fontname': 'Helvetica',
            'fontsize': '12',
            'splines': 'ortho',
            'nodesep': '0.4',
            'ranksep': '0.5',
            'compound': 'true'
        },
        node_attr={
            'fontname': 'Helvetica',
            'fontsize': '10',
            'style': 'filled'
        },
        edge_attr={
            'fontsize': '9'
        })
    
    # ===== PROMETHEUS =====
    with dot.subgraph(name='cluster_prometheus') as c:
        c.attr(label='Prometheus (Metrics Collection)',
              style='filled,rounded',
              color='lightgrey',
              fontsize='14')
        
        prometheus_port = compose_config['services']['prometheus']['ports'][0].split(':')[0]
        scrape_interval = prometheus_config.get('global', {}).get('scrape_interval', '15s')
        
        c.node('prometheus',
              label='Prometheus\n\n'
                    f"Port: {prometheus_port}\n"
                    f"Scrape Interval: {scrape_interval}\n"
                    f"Storage: /prometheus",
              shape='box',
              fillcolor='#ff9999')
    
    # ===== GRAFANA =====
    with dot.subgraph(name='cluster_grafana') as c:
        c.attr(label='Grafana (Visualization)',
              style='filled,rounded',
              color='lightgrey',
              fontsize='14')
        
        grafana_port = compose_config['services']['grafana']['ports'][0].split(':')[0]
        
        c.node('grafana',
              label='Grafana\n\n'
                    f"Port: {grafana_port}\n"
                    f"Auth: admin/admin\n"
                    f"Storage: /var/lib/grafana",
              shape='box',
              fillcolor='#99ccff')
    
    # ===== TARGETS =====
    with dot.subgraph(name='cluster_targets') as c:
        c.attr(label='Monitoring Targets',
              style='filled,rounded',
              color='lightgrey',
              fontsize='14')
        
        # Kafka Clients
        kafka_clients = prometheus_config['scrape_configs'][0]['static_configs'][0]['targets']
        for i, client in enumerate(kafka_clients):
            c.node(f'client_{i}',
                  label=f'Kafka Client\n{client}',
                  shape='box',
                  fillcolor='#88ff88')
            
            dot.edge(f'client_{i}', 'prometheus',
                    label=f"Scrape: {scrape_interval}",
                    style='dashed',
                    color='#666666')
        
        # Kafka Service
        kafka_service = prometheus_config['scrape_configs'][1]['static_configs'][0]['targets'][0]
        c.node('kafka_service',
              label=f'Kafka Service\n{kafka_service}',
              shape='box',
              fillcolor='#ffcc66')
        
        dot.edge('kafka_service', 'prometheus',
                label=f"Scrape: {scrape_interval}",
                style='dashed',
                color='#666666')
    
    # ===== CONNECTIONS =====
    dot.edge('prometheus', 'grafana',
            label='Data Source',
            style='dashed',
            color='#ff6600')
    
    # ===== RESOURCE ALLOCATION =====
    with dot.subgraph(name='cluster_resources') as c:
        c.attr(label='Resource Allocation',
              style='filled,rounded',
              color='#f0f0f0',
              fontsize='14')
        
        # Prometheus Resources
        prom_resources = compose_config['services']['prometheus']['deploy']['resources']
        c.node('prom_resources',
              label='Prometheus Resources\n\n'
                    f"CPU: {prom_resources['limits']['cpus']} limit\n"
                    f"Memory: {prom_resources['limits']['memory']} limit",
              shape='note',
              fontsize='10')
        
        # Grafana Resources
        grafana_resources = compose_config['services']['grafana']['deploy']['resources']
        c.node('grafana_resources',
              label='Grafana Resources\n\n'
                    f"CPU: {grafana_resources['limits']['cpus']} limit\n"
                    f"Memory: {grafana_resources['limits']['memory']} limit",
              shape='note',
              fontsize='10')
    
    dot.edge('prometheus', 'prom_resources', style='dashed', color='gray50')
    dot.edge('grafana', 'grafana_resources', style='dashed', color='gray50')
    
    return dot

if __name__ == "__main__":
    # Path configuration
    base_dir = Path(__file__).parent
    compose_path = base_dir / "monitor" / "docker-compose.monitor.yml"
    prometheus_path = base_dir / "monitor" / "prometheus.yml"
    
    # Parse configurations
    compose_config = parse_compose_config(compose_path)
    prometheus_config = parse_prometheus_config(prometheus_path)
    
    # Generate diagram
    diagram = build_monitoring_diagram(compose_config, prometheus_config)
    
    # Render and display
    try:
        output_path = diagram.render('monitor_diagram', 
                                   view=True, 
                                   cleanup=True,
                                   format='png',
                                   engine='dot')
        print(f"Successfully generated monitoring diagram: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
