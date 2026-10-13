-- Decision comments are part of the frozen LabelDecision resource and must survive the transaction.
ALTER TABLE label_decision ADD COLUMN comments VARCHAR(1000) NULL;
