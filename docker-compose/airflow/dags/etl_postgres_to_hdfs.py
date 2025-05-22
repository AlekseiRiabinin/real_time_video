from airflow import DAG
from airflow.providers.postgres.operators.postgres import PostgresOperator
from airflow.operators.bash import BashOperator
from airflow.utils.dates import days_ago
from airflow.providers.apache.hdfs.hooks.hdfs import HDFSHook


default_args = {
    'owner': 'airflow',
}

with DAG(
    dag_id='etl_postgres_to_hdfs',
    default_args=default_args,
    schedule_interval=None,  # Manual trigger
    start_date=days_ago(1),
    catchup=False,
    description='Extract data from Postgres and save it to HDFS',
) as dag:

    # Step 1: Export Postgres table to CSV (inside the container)
    export_postgres_to_csv = PostgresOperator(
        task_id='export_postgres_to_csv',
        postgres_conn_id='etl_postgres',
        sql="""
            COPY sample_table TO '/tmp/sample_table.csv' DELIMITER ',' CSV HEADER;
        """,
    )

    # Step 2: Upload the CSV file to HDFS (via Bash)
    def upload_to_hdfs():
        hook = HDFSHook(hdfs_conn_id='webhdfs_default')
        client = hook.get_conn()
        with open('/tmp/sample_table.csv', 'rb') as f:
            client.write('/data/sample_table.csv', f, overwrite=True)

    export_postgres_to_csv >> upload_to_hdfs
