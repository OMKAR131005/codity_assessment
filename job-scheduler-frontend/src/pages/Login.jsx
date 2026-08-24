import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function Login() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')

  async function handle(action) {
    setError('')
    try {
      if (action === 'login') await login(email, password)
      else await register(name, email, password)
      navigate('/')
    } catch (e) {
      setError(e.response?.data?.message || e.message)
    }
  }

  return (
    <div className="auth-wrap">
      <div className="panel" style={{ maxWidth: 360, width: '100%' }}>
        <h2>job://scheduler — auth</h2>
        <label>Name (register only)</label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Omkar" />
        <label>Email</label>
        <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="omkar@test.com" />
        <label>Password</label>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="row">
          <button onClick={() => handle('register')}>Register</button>
          <button className="secondary" onClick={() => handle('login')}>Login</button>
        </div>
        {error && <div className="msg err">{error}</div>}
      </div>
    </div>
  )
}
