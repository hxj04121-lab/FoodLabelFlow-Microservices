-- Local mirror of the RDS bootstrap job (architecture v3 §7.1): one database and one user per
-- service, each user granted privileges on its own database only. Local-only passwords.
CREATE DATABASE IF NOT EXISTS specification;
CREATE DATABASE IF NOT EXISTS formulation;
CREATE DATABASE IF NOT EXISTS compliance;
CREATE DATABASE IF NOT EXISTS label_workflow;

CREATE USER IF NOT EXISTS 'specification'@'%' IDENTIFIED BY 'local-only-specification';
CREATE USER IF NOT EXISTS 'formulation'@'%' IDENTIFIED BY 'local-only-formulation';
CREATE USER IF NOT EXISTS 'compliance'@'%' IDENTIFIED BY 'local-only-compliance';
CREATE USER IF NOT EXISTS 'label_workflow'@'%' IDENTIFIED BY 'local-only-label_workflow';

GRANT ALL PRIVILEGES ON specification.* TO 'specification'@'%';
GRANT ALL PRIVILEGES ON formulation.* TO 'formulation'@'%';
GRANT ALL PRIVILEGES ON compliance.* TO 'compliance'@'%';
GRANT ALL PRIVILEGES ON label_workflow.* TO 'label_workflow'@'%';
FLUSH PRIVILEGES;
