import { createContext, useContext, useState } from 'react'
import client from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(localStorage.getItem('js_token'))

  async function login(email, password) {
    const { data } = await client.post('/auth/login', { email, password })
    localStorage.setItem('js_token', data.token)
    setToken(data.token)
  }

  async function register(name, email, password) {
    const { data } = await client.post('/auth/register', { name, email, password })
    localStorage.setItem('js_token', data.token)
    setToken(data.token)
  }

  function logout() {
    localStorage.removeItem('js_token')
    setToken(null)
  }

  return (
    <AuthContext.Provider value={{ token, login, register, logout, isAuthed: !!token }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
