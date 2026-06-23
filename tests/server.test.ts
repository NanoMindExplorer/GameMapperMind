import { describe, it, expect } from 'vitest';
import request from 'supertest';
import app from '../server';

describe('Server Tests', () => {
    it('should return 401 without auth', async () => {
        const res = await request(app).get('/api/logs');
        expect(res.status).toBe(401);
    });

    it('should return 200 with auth', async () => {
        let token = process.env.ADMIN_TOKEN;
        if (!token) {
            token = require('fs').readFileSync('.admin_token', 'utf-8').trim();
        }
        const res = await request(app)
            .get('/api/logs')
            .set('Authorization', `Bearer ${token}`);
        expect(res.status).toBe(200);
    });

    it('should return 401 without auth for macros', async () => {
        const res = await request(app).get('/api/macros');
        expect(res.status).toBe(401);
    });

    it('should return 200 with auth for macros', async () => {
        let token = process.env.ADMIN_TOKEN;
        if (!token) {
            token = require('fs').readFileSync('.admin_token', 'utf-8').trim();
        }
        const res = await request(app)
            .get('/api/macros')
            .set('Authorization', `Bearer ${token}`);
        expect(res.status).toBe(200);
    });
});
