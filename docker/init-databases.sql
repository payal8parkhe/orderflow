-- Automated PostgreSQL multi-database provisioning for OrderFlow Microservices
SELECT 'CREATE DATABASE orderflow_order_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderflow_order_db')\gexec
SELECT 'CREATE DATABASE orderflow_payment_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderflow_payment_db')\gexec
SELECT 'CREATE DATABASE orderflow_delivery_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderflow_delivery_db')\gexec
SELECT 'CREATE DATABASE orderflow_notification_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderflow_notification_db')\gexec
SELECT 'CREATE DATABASE orderflow_catalog_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderflow_catalog_db')\gexec

GRANT ALL PRIVILEGES ON DATABASE orderflow_order_db TO orderflow_user;
GRANT ALL PRIVILEGES ON DATABASE orderflow_payment_db TO orderflow_user;
GRANT ALL PRIVILEGES ON DATABASE orderflow_delivery_db TO orderflow_user;
GRANT ALL PRIVILEGES ON DATABASE orderflow_notification_db TO orderflow_user;
GRANT ALL PRIVILEGES ON DATABASE orderflow_catalog_db TO orderflow_user;
