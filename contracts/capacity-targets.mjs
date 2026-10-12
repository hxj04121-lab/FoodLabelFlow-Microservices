import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const json = path => JSON.parse(readFileSync(resolve(here, path), 'utf8'));
function check(name, action) { action(); console.log('PASS ' + name); }

export function validateCapacityTargets() {
const targets = json('../docs/swe5001/test-plan-G0-M2-targets.v1.json');
  assert.equal(targets.declaredAt, '2026-10-01');
  assert.equal(targets.sourceCommit, json('source-manifest.json').sourceCommit);
  assert(targets.source.includes('/final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md'));
  assert.deepEqual(targets.dataset, { seed: 5001, releasedFormulaVersions: 5000, manufacturers: 20, formulaVersionsPerManufacturer: 250 });
  assert.deepEqual(targets.replicas, [1, 3]);
  assert.equal(targets.perReplica.cpuLimit, '1'); assert.equal(targets.perReplica.memoryLimit, '2Gi');
  assert.deepEqual(targets.slo, { p95MillisecondsMaximum: 2000, errorFractionExclusiveMaximum: 0.01, droppedIterationsMaximum: 0 });
  assert.equal(targets.scaleOut.minimumThreeToOneCapacityRatio, 2);
  assert.equal(targets.failover.loadFractionOfMeasuredThreeReplicaCapacity, 0.5);
  assert.equal(targets.failover.errorFractionExclusiveMaximum, 0.01);
  assert.equal(targets.failover.continuousErrorWindowSecondsMaximum, 10);
  assert.equal(targets.failover.sloRecoverySecondsMaximum, 60);
  assert.equal(targets.failover.replacementReadySecondsExclusiveMaximum, 180);
}
