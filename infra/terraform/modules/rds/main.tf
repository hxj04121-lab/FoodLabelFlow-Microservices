resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-mysql"
  subnet_ids = var.subnet_ids
  tags       = merge(var.tags, { Name = "${var.name}-mysql" })
}

resource "aws_cloudwatch_log_group" "rds" {
  for_each          = toset(["error", "general", "slowquery"])
  name              = "/aws/rds/instance/${var.name}-mysql/${each.value}"
  retention_in_days = 14
  tags              = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-mysql"
  description = "Only EKS nodes may connect to MySQL"
  vpc_id      = var.vpc_id

  ingress {
    description     = "MySQL from EKS nodes"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [var.allowed_source_sg]
  }
  tags = merge(var.tags, { Name = "${var.name}-mysql" })
}

resource "random_password" "master" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_instance" "this" {
  identifier                      = "${var.name}-mysql"
  engine                          = "mysql"
  engine_version                  = "8.0"
  instance_class                  = "db.t3.small"
  allocated_storage               = 20
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = var.database_name
  username                        = "spectrace_admin"
  password                        = random_password.master.result
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.this.id]
  publicly_accessible             = false
  multi_az                        = true
  backup_retention_period         = 1
  auto_minor_version_upgrade      = true
  apply_immediately               = true
  deletion_protection             = false
  skip_final_snapshot             = true
  delete_automated_backups        = true
  enabled_cloudwatch_logs_exports = ["error", "general", "slowquery"]
  tags                            = merge(var.tags, { Name = "${var.name}-mysql" })

  depends_on = [aws_cloudwatch_log_group.rds]
}

resource "aws_secretsmanager_secret" "master" {
  name                    = "${var.name}/mysql/master"
  recovery_window_in_days = 0
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "master" {
  secret_id = aws_secretsmanager_secret.master.id
  secret_string = jsonencode({
    username = aws_db_instance.this.username
    password = random_password.master.result
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
    database = var.database_name
  })
}
