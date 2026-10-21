#!/usr/bin/env node
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'

const MAGIC = Buffer.from('FFTFP01\n')
const ALGORITHM = 'aes-256-gcm'
const SHA256 = /^[a-f0-9]{64}$/
const COMMIT = /^[a-f0-9]{40}$/

function fail(message) {
  throw new Error(message)
}

function keyFromEnv() {
  const encoded = process.env.TF_PLAN_ENCRYPTION_KEY?.trim()
  if (!encoded) fail('TF_PLAN_ENCRYPTION_KEY is required.')
  const key = Buffer.from(encoded, 'base64')
  if (key.length !== 32 || key.toString('base64') !== encoded) fail('TF_PLAN_ENCRYPTION_KEY must be canonical base64 for 32 random bytes.')
  return key
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function readMetadata(path) {
  let metadata
  try {
    metadata = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    fail('Plan metadata is missing or invalid JSON.')
  }
  if (!metadata || metadata.schemaVersion !== 1 || metadata.algorithm !== ALGORITHM) fail('Plan metadata schema or algorithm is unsupported.')
  if (!SHA256.test(metadata.planSha256 ?? '') || !SHA256.test(metadata.encryptedSha256 ?? '')) fail('Plan metadata contains an invalid digest.')
  if (!COMMIT.test(metadata.commitSha ?? '') || !SHA256.test(metadata.providerLockSha256 ?? '')) fail('Plan metadata does not pin a valid commit and provider lockfile.')
  for (const field of ['terraformVersion', 'stateVersionId', 'stateObjectKey', 'workspace', 'stateBucket', 'awsAccountId', 'awsRegion']) {
    if (typeof metadata[field] !== 'string' || metadata[field].length === 0) fail(`Plan metadata is missing ${field}.`)
  }
  return metadata
}

function writeExclusive(path, content) {
  writeFileSync(path, content, { flag: 'wx', mode: 0o600 })
}

function encrypt() {
  const input = process.env.TF_PLAN_FILE
  const encryptedPath = process.env.TF_PLAN_ENCRYPTED_FILE
  const metadataPath = process.env.TF_PLAN_METADATA_FILE
  if (!input || !encryptedPath || !metadataPath) fail('Plan encryption file paths are required.')
  const plan = readFileSync(input)
  if (!plan.length) fail('Terraform Plan file is empty.')
  const iv = randomBytes(12)
  const cipher = createCipheriv(ALGORITHM, keyFromEnv(), iv)
  const ciphertext = Buffer.concat([cipher.update(plan), cipher.final()])
  const encrypted = Buffer.concat([MAGIC, iv, cipher.getAuthTag(), ciphertext])
  const metadata = {
    schemaVersion: 1,
    algorithm: ALGORITHM,
    planSha256: sha256(plan),
    encryptedSha256: sha256(encrypted),
    commitSha: process.env.M5_TF_PLAN_COMMIT_SHA,
    terraformVersion: process.env.M5_TF_PLAN_TERRAFORM_VERSION,
    providerLockSha256: process.env.M5_TF_PLAN_PROVIDER_LOCK_SHA256,
    stateVersionId: process.env.M5_TF_PLAN_STATE_VERSION_ID,
    stateObjectKey: process.env.M5_TF_PLAN_STATE_OBJECT_KEY,
    stateBucket: process.env.M5_TF_PLAN_STATE_BUCKET,
    workspace: process.env.M5_TF_PLAN_WORKSPACE,
    awsAccountId: process.env.M5_TF_PLAN_AWS_ACCOUNT_ID,
    awsRegion: process.env.M5_TF_PLAN_AWS_REGION,
  }
  const metadataBytes = Buffer.from(JSON.stringify(metadata, null, 2) + '\n')
  writeExclusive(encryptedPath, encrypted)
  try {
    writeExclusive(metadataPath, metadataBytes)
  } catch (error) {
    fail(`Could not write encrypted Plan metadata: ${error.message}`)
  }
  readMetadata(metadataPath)
  console.log(`Encrypted Terraform Plan sha256:${metadata.planSha256}; ciphertext sha256:${metadata.encryptedSha256}`)
}

function verifyExpected(metadata) {
  const expected = {
    planSha256: process.env.EXPECTED_PLAN_SHA256,
    commitSha: process.env.EXPECTED_COMMIT_SHA,
    stateVersionId: process.env.EXPECTED_STATE_VERSION_ID,
    terraformVersion: process.env.EXPECTED_TERRAFORM_VERSION,
    providerLockSha256: process.env.EXPECTED_PROVIDER_LOCK_SHA256,
    stateObjectKey: process.env.EXPECTED_STATE_OBJECT_KEY,
    stateBucket: process.env.EXPECTED_STATE_BUCKET,
    workspace: process.env.EXPECTED_TF_WORKSPACE,
    awsAccountId: process.env.EXPECTED_AWS_ACCOUNT_ID,
    awsRegion: process.env.EXPECTED_AWS_REGION,
  }
  for (const [field, value] of Object.entries(expected)) {
    if (!value || metadata[field] !== value) fail(`Plan approval mismatch for ${field}.`)
  }
}

function decrypt() {
  const encryptedPath = process.env.TF_PLAN_ENCRYPTED_FILE
  const metadataPath = process.env.TF_PLAN_METADATA_FILE
  const outputPath = process.env.TF_PLAN_FILE
  if (!encryptedPath || !metadataPath || !outputPath) fail('Plan decryption file paths are required.')
  const metadata = readMetadata(metadataPath)
  verifyExpected(metadata)
  const encrypted = readFileSync(encryptedPath)
  if (sha256(encrypted) !== metadata.encryptedSha256 || encrypted.length < MAGIC.length + 12 + 16) fail('Encrypted Plan digest or length does not match metadata.')
  if (!encrypted.subarray(0, MAGIC.length).equals(MAGIC)) fail('Encrypted Plan envelope version is unsupported.')
  const ivStart = MAGIC.length
  const tagStart = ivStart + 12
  const dataStart = tagStart + 16
  const decipher = createDecipheriv(ALGORITHM, keyFromEnv(), encrypted.subarray(ivStart, tagStart))
  decipher.setAuthTag(encrypted.subarray(tagStart, dataStart))
  const plan = Buffer.concat([decipher.update(encrypted.subarray(dataStart)), decipher.final()])
  if (sha256(plan) !== metadata.planSha256 || metadata.planSha256 !== process.env.EXPECTED_PLAN_SHA256) fail('Decrypted Plan does not match the exact approved SHA-256.')
  writeExclusive(outputPath, plan)
  console.log(`Decrypted and verified approved Plan sha256:${metadata.planSha256} for ${metadata.commitSha}.`)
}

try {
  if (process.argv.length !== 3 || !['encrypt', 'decrypt'].includes(process.argv[2])) fail('Usage: secure_tfplan.mjs <encrypt|decrypt>.')
  if (process.argv[2] === 'encrypt') encrypt()
  else decrypt()
} catch (error) {
  console.error(`Secure Terraform Plan handling failed: ${error.message}`)
  process.exitCode = 1
}
