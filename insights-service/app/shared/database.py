import psycopg
from pgvector.psycopg import register_vector

from app.shared.config import DB_URL

class DatabaseConnectionFactory:
    """
    Creates database connections that understand vector columns
    """
    def __init__(self, database_url: str = DB_URL):
        self.database_url = database_url

    def create_connection(self) -> psycopg.Connection:
        """
        open a connection with pgvector's type adapters registered
        """
        database_connection = psycopg.connect(self.database_url)
        register_vector(database_connection)
        return database_connection