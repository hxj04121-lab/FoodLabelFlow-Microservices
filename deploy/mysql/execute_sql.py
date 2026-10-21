#!/usr/bin/env python3
"""Run a bootstrap SQL file against MySQL over TLS when the mysql CLI is unavailable (self-hosted runner)."""

import os
import ssl
import sys

import pymysql


def statements(text):
    return [part.strip() for part in text.split(";\n") if part.strip()]


def main(path):
    context = ssl.create_default_context()
    # Matches `mysql --ssl-mode=REQUIRED`: encrypted, without CA verification of the RDS certificate.
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = pymysql.connect(
        host=os.environ["MYSQL_HOST"], user=os.environ["MYSQL_ADMIN_USER"],
        password=os.environ["MYSQL_ADMIN_PASSWORD"], ssl=context, connect_timeout=20, autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SHOW STATUS LIKE 'Ssl_cipher'")
            row = cursor.fetchone()
            if not row or not row[1]:
                raise SystemExit("MySQL connection is not encrypted; refusing to send credentials.")
            with open(path, encoding="utf-8") as handle:
                for statement in statements(handle.read()):
                    cursor.execute(statement)
    finally:
        connection.close()


if __name__ == "__main__":
    main(sys.argv[1])
