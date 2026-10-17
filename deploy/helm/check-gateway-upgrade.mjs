import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parseAllDocuments} from 'yaml';

const EXPECTED_ORIGINS = 'https://hxj04121-lab.github.io,https://staging.52zzx.top';

function manifest(text, label, namespace) {
  const result = new Map();
  for (const document of parseAllDocuments(text)) {
    assert.equal(document.errors.length, 0, label + ': ' + document.errors.join('\n'));
    const resource = document.toJSON();
    if (!resource) continue;
    assert(resource.kind && resource.metadata?.name, label + ': every resource needs kind and metadata.name');
    resource.metadata.namespace ??= namespace;
    const key = [resource.kind, resource.metadata.namespace, resource.metadata.name].join('/');
    assert(!result.has(key), label + ': duplicate ' + key);
    result.set(key, resource);
  }
  return result;
}

function envByName(container, label) {
  const env = container.env ?? [];
  const result = new Map(env.map(item => [item.name, item]));
  assert.equal(result.size, env.length, label + ': duplicate container environment names');
  return result;
}

export function checkGatewayUpgrade(beforeText, desiredText) {
  const before = manifest(beforeText, 'installed manifest', 'gateway');
  const desired = manifest(desiredText, 'rendered manifest', 'gateway');
  const beforeKeys = [...before.keys()].sort();
  const desiredKeys = [...desired.keys()].sort();
  assert.deepEqual(desiredKeys, beforeKeys, 'Gateway upgrade must not add or remove Helm resources');

  let previousOrigins;
  for (const key of beforeKeys) {
    const current = before.get(key);
    const next = desired.get(key);
    assert.equal(next.apiVersion, current.apiVersion, key + ': API version changed');
    if (current.kind === 'Deployment' && current.metadata.name === 'gateway-gateway') {
      const oldContainers = current.spec?.template?.spec?.containers ?? [];
      const newContainers = next.spec?.template?.spec?.containers ?? [];
      assert.equal(oldContainers.length, newContainers.length, 'Gateway container count changed');
      for (const oldContainer of oldContainers) {
        const newContainer = newContainers.find(container => container.name === oldContainer.name);
        assert(newContainer, 'Gateway container was removed: ' + oldContainer.name);
        assert.equal(newContainer.image, oldContainer.image, 'Gateway image digest must remain unchanged');

        const oldEnv = envByName(oldContainer, 'installed Gateway');
        const newEnv = envByName(newContainer, 'rendered Gateway');
        assert.deepEqual([...newEnv.keys()].sort(), [...oldEnv.keys()].sort(), 'Gateway environment variables changed');
        const currentOrigins = oldEnv.get('GATEWAY_ALLOWED_ORIGINS')?.value;
        assert(
          currentOrigins === 'https://hxj04121-lab.github.io' || currentOrigins === EXPECTED_ORIGINS,
          'Current Gateway CORS origins must be the Pages origin or the already-upgraded Pages and staging origins',
        );
        for (const name of oldEnv.keys()) {
          if (name === 'GATEWAY_ALLOWED_ORIGINS') continue;
          assert.deepEqual(newEnv.get(name), oldEnv.get(name), 'Gateway setting changed: ' + name);
        }
        previousOrigins = currentOrigins;
        assert.equal(newEnv.get('GATEWAY_ALLOWED_ORIGINS')?.value, EXPECTED_ORIGINS,
          'Gateway must allow the Pages and staging root origins');
      }

      const stripEnv = resource => {
        const copy = structuredClone(resource);
        for (const container of copy.spec.template.spec.containers) delete container.env;
        return copy;
      };
      assert.deepEqual(stripEnv(next), stripEnv(current), 'Gateway may change only GATEWAY_ALLOWED_ORIGINS');
    } else {
      assert.deepEqual(next, current, key + ' must render identically before the upgrade');
    }
  }

  console.log('PASS: ' + beforeKeys.length + ' Gateway resources remain unchanged in identity; only the approved CORS origin may change.');
  if (previousOrigins !== EXPECTED_ORIGINS) {
    console.log('- GATEWAY_ALLOWED_ORIGINS: ' + previousOrigins + ' -> ' + EXPECTED_ORIGINS);
  }
}

if (process.argv[1]?.endsWith('/check-gateway-upgrade.mjs')) {
  assert.equal(process.argv.length, 4, 'Usage: node check-gateway-upgrade.mjs INSTALLED.yaml RENDERED.yaml');
  checkGatewayUpgrade(readFileSync(process.argv[2], 'utf8'), readFileSync(process.argv[3], 'utf8'));
}
