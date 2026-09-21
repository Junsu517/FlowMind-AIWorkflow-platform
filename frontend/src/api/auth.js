import request from './index'

/** 契约：POST /api/auth/register -> 201 { token, user } */
export function register(data) {
  return request.post('/auth/register', data).then((res) => res.data)
}

/** 契约：POST /api/auth/login -> 200 { token, user } */
export function login(data) {
  return request.post('/auth/login', data).then((res) => res.data)
}

/** 契约：GET /api/auth/me -> 200 user */
export function getMe() {
  return request.get('/auth/me').then((res) => res.data)
}
