/**
 * SyncClient — calls ListenStat Sync API from the Electron main process.
 * Never logs the bearer token.
 */
import https from 'https';
import http from 'http';
import { URL } from 'url';

const DEFAULT_BASE = 'https://jiketianxiawang.top';

export class SyncClient {
  constructor(baseUrl = DEFAULT_BASE) {
    this.baseUrl = (baseUrl || DEFAULT_BASE).replace(/\/$/, '');
  }

  async health() {
    const j = await this._request('GET', '/v1/health');
    return Boolean(j?.ok);
  }

  async register(deviceId, displayName) {
    const body = { device_id: deviceId };
    if (displayName) body.display_name = displayName;
    return this._request('POST', '/v1/register', { body });
  }

  async getMe(token) {
    return this._request('GET', '/v1/me', { token });
  }

  async patchMe(token, displayName) {
    return this._request('PATCH', '/v1/me', { token, body: { display_name: displayName } });
  }

  async push(token, records) {
    return this._request('POST', '/v1/sync/push', { token, body: { records } });
  }

  async pull(token, since) {
    const path = since
      ? `/v1/sync/pull?since=${encodeURIComponent(since)}`
      : '/v1/sync/pull';
    return this._request('GET', path, { token });
  }

  async pairCreate(token) {
    return this._request('POST', '/v1/pair/create', { token, body: {} });
  }

  async pairClaim(token, code) {
    return this._request('POST', '/v1/pair/claim', { token, body: { code } });
  }

  _request(method, path, { token, body } = {}) {
    return new Promise((resolve, reject) => {
      const url = new URL(this.baseUrl + path);
      const isHttps = url.protocol === 'https:';
      const lib = isHttps ? https : http;
      const headers = {
        Accept: 'application/json',
        'User-Agent': 'ListenStat-Desktop',
      };
      let payload = null;
      if (body !== undefined) {
        payload = Buffer.from(JSON.stringify(body), 'utf8');
        headers['Content-Type'] = 'application/json; charset=utf-8';
        headers['Content-Length'] = payload.length;
      }
      if (token) headers.Authorization = `Bearer ${token}`;

      const req = lib.request(
        {
          protocol: url.protocol,
          hostname: url.hostname,
          port: url.port || (isHttps ? 443 : 80),
          path: url.pathname + url.search,
          method,
          headers,
          timeout: 30000,
        },
        (res) => {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => {
            const raw = Buffer.concat(chunks).toString('utf8') || '{}';
            let json;
            try {
              json = JSON.parse(raw);
            } catch {
              json = { detail: raw };
            }
            if (res.statusCode >= 400) {
              const err = new Error(json.detail || `HTTP ${res.statusCode}`);
              err.code = res.statusCode;
              reject(err);
              return;
            }
            resolve(json);
          });
        }
      );
      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy();
        reject(new Error('timeout'));
      });
      if (payload) req.write(payload);
      req.end();
    });
  }
}

export const syncClient = new SyncClient();
