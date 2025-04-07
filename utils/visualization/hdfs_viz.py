import graphviz
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_hdfs_configs(hdfs_site_path, core_site_path):
    """Extract comprehensive HDFS configuration parameters"""
    config = {}
    
    # Parse hdfs-site.xml
    hdfs_tree = ET.parse(hdfs_site_path)
    for prop in hdfs_tree.findall('.//property'):
        name = prop.find('name').text
        value = prop.find('value').text
        config[name] = value
    
    # Parse core-site.xml
    core_tree = ET.parse(core_site_path)
    for prop in core_tree.findall('.//property'):
        name = prop.find('name').text
        value = prop.find('value').text
        config[name] = value
    
    # Add derived configurations
    config['namenode_dirs'] = config.get('dfs.namenode.name.dir', '/hadoop/dfs/name').split(',')
    config['datanode_dirs'] = config.get('dfs.datanode.data.dir', '/hadoop/dfs/data').split(',')
    
    return config

def build_enhanced_hdfs_diagram(services, hdfs_config, compose_config):
    """Generate comprehensive HDFS diagram with all components and configurations"""
    dot = graphviz.Digraph('HDFSArchitecture',
        format='png',
        graph_attr={
            'rankdir': 'TB',
            'fontname': 'Helvetica',
            'fontsize': '14',  # Increased from default
            'splines': 'ortho',
            'nodesep': '0.4',
            'ranksep': '0.5',
            'compound': 'true'
        },
        node_attr={
            'fontname': 'Helvetica',
            'fontsize': '12',  # Increased from 10
            'style': 'filled'
        },
        edge_attr={
            'fontsize': '11'  # Added edge font size
        })
    
    # ===== MAIN HDFS CLUSTER =====
    with dot.subgraph(name='cluster_hdfs') as c:
        c.attr(label='HDFS Cluster (Block Storage System)',
              style='filled,rounded',
              color='lightgrey',
              fontsize='16')  # Increased from 12
        
        # Enhanced Namenode
        c.node('namenode',
              label='NameNode\n(Metadata Manager)\n\n' +
                    'EditLog/FSImage:\n' + 
                    f"{hdfs_config['namenode_dirs'][0]}\n\n" +
                    f"Ports:\n" +
                    f"RPC: {hdfs_config['fs.defaultFS'].split(':')[-1]}\n" +
                    f"HTTP: {hdfs_config.get('dfs.namenode.http-address', '9870')}",
              shape='box',
              fillcolor='#e6f3ff',
              fontsize='14')  # Specific size for this node
        
        # Enhanced Datanodes
        datanode_count = sum(1 for svc in services if svc.startswith('datanode'))
        for i in range(datanode_count):
            c.node(f'datanode{i}',
                  label=f'DataNode{i+1}\n(Block Storage)\n\n' +
                        f"Data Dirs: {len(hdfs_config['datanode_dirs'])}\n\n" +
                        f"Ports:\n" +
                        f"HTTP: {hdfs_config.get('dfs.datanode.http.address', '9864')}\n" +
                        f"Aux: 1004/1006",
                  shape='box',
                  fillcolor='#e6ffe6',
                  fontsize='14')  # Specific size for these nodes
    
    # ===== EDGES WITH CLEAR LABELS =====
    replication_factor = int(hdfs_config.get('dfs.replication', 1))
    
    for i in range(datanode_count):
        dot.edge('namenode', f'datanode{i}',
                label=f'Block Assignment\nReplication: {replication_factor}',
                style='dashed',
                color='blue',
                fontsize='12')  # Increased from 10
        
        if i > 0:
            dot.edge(f'datanode{i-1}', f'datanode{i}',
                    label='Replication Pipeline\n(64MB chunks)',
                    dir='both',
                    color='green',
                    fontsize='12')  # Increased from 10
    
    # ===== CONFIGURATION DETAILS =====
    with dot.subgraph(name='cluster_config') as c:
        c.attr(label='HDFS Configuration',
              style='filled,rounded',
              color='#f0f0f0',
              fontsize='14')  # Increased from 10
        
        # Security Configuration
        c.node('security_cfg',
              label='Security Configuration\n\n' +
                    f"Auth: {hdfs_config.get('hadoop.security.authentication', 'simple')}\n" +
                    f"Perms: {hdfs_config.get('dfs.permissions.enabled', 'false')}\n" +
                    f"WebHDFS: {hdfs_config.get('dfs.webhdfs.enabled', 'false')}\n" +
                    f"Hostname Check: {hdfs_config.get('dfs.namenode.datanode.registration.ip-hostname-check', 'false')}",
              shape='note',
              fontsize='12')  # Specific size for this node
        
        # Storage Configuration
        c.node('storage_cfg',
              label='Storage Configuration\n\n' +
                    f"Replication: {replication_factor}\n" +
                    f"Block Size: 128MB\n" +
                    f"NN Dir: {hdfs_config['namenode_dirs'][0]}\n" +
                    f"DN Dirs: {len(hdfs_config['datanode_dirs'])}",
              shape='note',
              fontsize='12')  # Specific size for this node
    
    # # ===== RESOURCE ALLOCATION =====
    # with dot.subgraph(name='cluster_resources') as c:
    #     c.attr(label='Container Resources',
    #           style='filled,rounded',
    #           color='#fff0f0',
    #           fontsize='14')  # Increased from 10
        
    #     # Namenode Resources
    #     c.node('nn_resources',
    #           label='NameNode Resources\n\n' +
    #                 'CPU: 0.5 limit / 0.25 reserved\n' +
    #                 'Memory: 1GB limit / 512MB reserved',
    #           shape='note',
    #           fontsize='12')  # Specific size for this node
        
    #     # Datanode Resources
    #     c.node('dn_resources',
    #           label='DataNode Resources\n\n' +
    #                 'CPU: 1 limit / 0.5 reserved\n' +
    #                 'Memory: 2GB limit / 1GB reserved',
    #           shape='note',
    #           fontsize='12')  # Specific size for this node
    
    # ===== NETWORK CONFIGURATION =====
    dot.node('network_info',
            label='Network Configuration\n\n' +
                  f"Cluster Network: {compose_config.get('networks', {}).get('kafka-net', {}).get('driver', 'bridge')}\n" +
                  'Port Mappings:\n' +
                  '- NN Web: 9870\n' +
                  '- NN RPC: 8020\n' +
                  '- DN Web: 9864\n' +
                  '- DN Aux: 1004/1006',
            shape='note',
            fillcolor='#f0f0f0',
            fontsize='12')  # Specific size for this node
    
    # ===== VOLUME CONFIGURATION =====
    with dot.subgraph(name='cluster_volumes') as c:
        c.attr(label='Persistent Volumes',
              style='filled,rounded',
              color='#f0fff0',
              fontsize='14')  # Increased from 10
        
        c.node('nn_volumes',
              label='NameNode Volumes\n\n' +
                    'namenode-data: /hadoop/dfs/name\n' +
                    'namenode-logs: /usr/local/hadoop/logs',
              shape='note',
              fontsize='12')  # Specific size for this node
        
        c.node('dn_volumes',
              label='DataNode Volumes\n\n' +
                    'datanode-data: /hadoop/dfs/data\n' +
                    'datanode-logs: /usr/local/hadoop/logs',
              shape='note',
              fontsize='12')  # Specific size for this node
    
    # Connect components
    dot.edge('namenode', 'security_cfg', style='dashed', color='gray50')
    dot.edge('namenode', 'nn_resources', style='dashed', color='gray50')
    dot.edge('namenode', 'nn_volumes', style='dashed', color='gray50')
    dot.edge('datanode0', 'dn_resources', style='dashed', color='gray50')
    dot.edge('datanode0', 'dn_volumes', style='dashed', color='gray50')
    dot.edge('network_info', 'namenode', style='dashed', color='gray50')
    
    return dot

def parse_compose_config(compose_path):
    """Parse basic compose file information"""
    # In a real implementation, you would parse the YAML file properly
    # For this example, we'll return a simplified structure
    return {
        'networks': {
            'kafka-net': {
                'driver': 'bridge'
            }
        },
        'services': {
            'namenode': {
                'ports': ['9870:9870', '8020:8020'],
                'volumes': ['namenode-data:/hadoop/dfs/name', 'namenode-logs:/usr/local/hadoop/logs']
            },
            'datanode': {
                'ports': ['9864:9864', '1004:1004', '1006:1006'],
                'volumes': ['datanode-data:/hadoop/dfs/data', 'datanode-logs:/usr/local/hadoop/logs']
            }
        }
    }

if __name__ == "__main__":
    # Path configuration
    base_dir = Path(__file__).parent
    compose_path = base_dir / "hdfs" / "docker-compose.hdfs.yml"
    hdfs_site_path = base_dir / "hdfs" / "hdfs-site.xml"
    core_site_path = base_dir / "hdfs" / "core-site.xml"
    
    # Parse configurations
    hdfs_config = parse_hdfs_configs(hdfs_site_path, core_site_path)
    compose_config = parse_compose_config(compose_path)
    
    # Generate diagram (using sample services - in practice parse from compose)
    services = ['namenode', 'datanode']
    diagram = build_enhanced_hdfs_diagram(services, hdfs_config, compose_config)
    
    # Render and display
    try:
        output_path = diagram.render('hdfs_diagram', 
                                   view=True, 
                                   cleanup=True,
                                   format='png',
                                   engine='dot')
        print(f"Successfully generated enhanced HDFS diagram: {output_path}")
    except Exception as e:
        print(f"Error generating diagram: {e}")
        exit(1)
