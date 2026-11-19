"""Bootstrap the SQL schema, also compatible with the previously imported SQL file."""

from importlib.resources import files

from alembic import op

revision = "0001_matching"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    sql = files("matching_service").joinpath("resources", "schema.sql").read_text(encoding="utf-8")
    # Alembic owns the transaction; asyncpg.execute supports this DDL batch.
    sql = sql.replace("\nBEGIN;\n", "\n", 1).removesuffix("COMMIT;\n")
    op.get_bind().connection.run_async(lambda connection: connection.execute(sql))


def downgrade():
    # Keep a downgrade explicit so operators cannot accidentally erase Saga state.
    raise RuntimeError("Destructive downgrade is unsupported; back up and migrate explicitly")
