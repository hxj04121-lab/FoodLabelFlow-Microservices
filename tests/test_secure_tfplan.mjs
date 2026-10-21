import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'

const script = resolve('scripts/staging/secure_tfplan.mjs')
const sha256 = (value) => createHash('sha256').update(value).digest('hex')

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'secure-tfplan-'))
  const key = randomBytes(32).toString('base64')
  const plan = Buffer.from('opaque saved Terraform plan fixture')
  const planPath = join(directory, 'staging.tfplan')
  const encryptedPath = join(directory, 'staging.tfplan.enc')
  const metadataPath = join(directory, 'metadata.json')
  writeFileSync(planPath, plan)
  const env = {
    ...process.env,
    TF_PLAN_ENCRYPTION_KEY: key,
    TF_PLAN_FILE: planPath,
    TF_PLAN_ENCRYPTED_FILE: encryptedPath,
    TF_PLAN_METADATA_FILE: metadataPath,
    M5_TF_PLAN_COMMIT_SHA: 'a'.repeat(40),
    M5_TF_PLAN_TERRAFORM_VERSION: '1.15.8',
    M5_TF_PLAN_PROVIDER_LOCK_SHA256: 'b'.repeat(64),
    M5_TF_PLAN_STATE_VERSION_ID: 'version-123',
    M5_TF_PLAN_STATE_OBJECT_KEY: 'spectrace/staging/terraform.tfstate',
    M5_TF_PLAN_STATE_BUCKET: 'foodlabelflow-staging-tfstate-679857346122-ap-southeast-1',
    M5_TF_PLAN_WORKSPACE: 'staging',
    M5_TF_PLAN_AWS_ACCOUNT_ID: '679857346122',
    M5_TF_PLAN_AWS_REGION: 'ap-southeast-1',
  }
  return { directory, key, plan, planPath, encryptedPath, metadataPath, env }
}

function run(mode, env) {
  return spawnSync(process.execPath, [script, mode], { env, encoding: 'utf8' })
}

function approvedEnv(fixture, changes = {}) {
  return {
    ...fixture.env,
    EXPECTED_PLAN_SHA256: sha256(fixture.plan),
    EXPECTED_COMMIT_SHA: 'a'.repeat(40),
    EXPECTED_STATE_VERSION_ID: 'version-123',
    EXPECTED_TERRAFORM_VERSION: '1.15.8',
    EXPECTED_PROVIDER_LOCK_SHA256: 'b'.repeat(64),
    EXPECTED_STATE_OBJECT_KEY: 'spectrace/staging/terraform.tfstate',
    EXPECTED_STATE_BUCKET: 'foodlabelflow-staging-tfstate-679857346122-ap-southeast-1',
    EXPECTED_TF_WORKSPACE: 'staging',
    EXPECTED_AWS_ACCOUNT_ID: '679857346122',
    EXPECTED_AWS_REGION: 'ap-southeast-1',
    ...changes,
  }
}

test('encrypts and decrypts only when all approved metadata matches', (t) => {
  const f = fixture()
  t.after(() => rmSync(f.directory, { recursive: true, force: true }))
  const encrypted = run('encrypt', f.env)
  assert.equal(encrypted.status, 0, encrypted.stderr)
  const metadata = JSON.parse(readFileSync(f.metadataPath, 'utf8'))
  assert.equal(metadata.planSha256, sha256(f.plan))
  assert.equal(metadata.encryptedSha256, sha256(readFileSync(f.encryptedPath)))
  assert.equal(JSON.stringify(metadata).includes(f.key), false)

  const outputPath = join(f.directory, 'decrypted.tfplan')
  const decrypted = run('decrypt', { ...approvedEnv(f), TF_PLAN_FILE: outputPath })
  assert.equal(decrypted.status, 0, decrypted.stderr)
  assert.deepEqual(readFileSync(outputPath), f.plan)
})

test('refuses an unapproved State VersionId before decrypting', (t) => {
  const f = fixture()
  t.after(() => rmSync(f.directory, { recursive: true, force: true }))
  assert.equal(run('encrypt', f.env).status, 0)
  const result = run('decrypt', approvedEnv(f, { EXPECTED_STATE_VERSION_ID: 'different-version' }))
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /Plan approval mismatch for stateVersionId/)
})

test('rejects changed ciphertext', (t) => {
  const f = fixture()
  t.after(() => rmSync(f.directory, { recursive: true, force: true }))
  assert.equal(run('encrypt', f.env).status, 0)
  writeFileSync(f.encryptedPath, Buffer.concat([readFileSync(f.encryptedPath), Buffer.from('tamper')]))
  const tampered = run('decrypt', approvedEnv(f))
  assert.notEqual(tampered.status, 0)
  assert.match(tampered.stderr, /Encrypted Plan digest or length/)
})

test('rejects an incorrect encryption key', (t) => {
  const f = fixture()
  t.after(() => rmSync(f.directory, { recursive: true, force: true }))
  assert.equal(run('encrypt', f.env).status, 0)
  const result = run('decrypt', approvedEnv(f, { TF_PLAN_ENCRYPTION_KEY: randomBytes(32).toString('base64') }))
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /Unsupported state or unable to authenticate data/)
})

test('rejects weak or malformed encryption keys', (t) => {
  const f = fixture()
  t.after(() => rmSync(f.directory, { recursive: true, force: true }))
  const result = run('encrypt', { ...f.env, TF_PLAN_ENCRYPTION_KEY: 'too-short' })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /canonical base64 for 32 random bytes/)
})
