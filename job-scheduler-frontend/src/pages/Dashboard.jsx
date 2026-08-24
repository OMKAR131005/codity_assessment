import { useState, useEffect, useCallback } from 'react'
import client from '../api/client'
import StatusBadge from '../components/StatusBadge'
import { useAuth } from '../context/AuthContext'

export default function Dashboard() {
  const { logout } = useAuth()

  // --- Project ---
  const [projectName, setProjectName] = useState('Notification System')
  const [projects, setProjects] = useState([])
  const [activeProjectId, setActiveProjectId] = useState(() => {
    const saved = localStorage.getItem('js_activeProjectId')
    return saved ? Number(saved) : null
  })
  const [projectMsg, setProjectMsg] = useState(null)

  // --- Queue ---
  const [queueForm, setQueueForm] = useState({
    name: 'order-notifications',
    priority: 8,
    concurrencyLimit: 5,
    retryType: 'EXPONENTIAL',
    baseDelaySeconds: 10,
    multiplier: 2.0,
    maxRetries: 3
  })
  const [queues, setQueues] = useState([])
  const [activeQueueId, setActiveQueueId] = useState(() => {
    const saved = localStorage.getItem('js_activeQueueId')
    return saved ? Number(saved) : null
  })
  const [queueMsg, setQueueMsg] = useState(null)

  // --- Job submission ---
  const [jobForm, setJobForm] = useState({
    queueId: '',
    jobType: 'IMMEDIATE',
    delaySeconds: 30,
    scheduledAt: '',
    priority: 5,
    payload: '{"type":"email","to":"user@x.com","message":"Order confirmed"}'
  })
  const [jobMsg, setJobMsg] = useState(null)

  // --- Jobs list / stats ---
  const [listQueueId, setListQueueId] = useState('')
  const [jobs, setJobs] = useState([])
  const [jobsMsg, setJobsMsg] = useState(null)
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [stats, setStats] = useState(null)

  // --- History ---
  const [historyJobId, setHistoryJobId] = useState('')
  const [history, setHistory] = useState([])

  const loadProjects = useCallback(async () => {
    try {
      const { data } = await client.get('/projects')
      setProjects(data)
    } catch (e) {
      setProjectMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }, [])

  const loadQueues = useCallback(async (projectId) => {
    const pId = projectId || activeProjectId
    if (!pId) {
      setQueues([])
      return
    }
    try {
      const { data } = await client.get(`/projects/${pId}/queues`)
      setQueues(data)
    } catch (e) {
      setQueueMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }, [activeProjectId])

  useEffect(() => {
    loadProjects()
    if (activeProjectId) loadQueues(activeProjectId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (activeProjectId) localStorage.setItem('js_activeProjectId', String(activeProjectId))
    else localStorage.removeItem('js_activeProjectId')
  }, [activeProjectId])

  useEffect(() => {
    if (activeQueueId) {
      localStorage.setItem('js_activeQueueId', String(activeQueueId))
      setListQueueId(String(activeQueueId))
    } else {
      localStorage.removeItem('js_activeQueueId')
    }
  }, [activeQueueId])

  function selectProject(id) {
    setActiveProjectId(id)
    setActiveQueueId(null)
    setQueues([])
    if (id) loadQueues(id)
  }

  async function createProject() {
    setProjectMsg(null)
    try {
      const { data } = await client.post('/projects', { name: projectName })
      setActiveProjectId(data.id)
      setProjectMsg({ ok: true, text: `Project created (id ${data.id}).` })
      loadProjects()
      loadQueues(data.id)
    } catch (e) {
      setProjectMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }

  async function createQueue() {
    if (!activeProjectId) {
      setQueueMsg({ ok: false, text: 'Create a project first.' })
      return
    }
    setQueueMsg(null)
    try {
      const { data } = await client.post(`/projects/${activeProjectId}/queues`, queueForm)
      setActiveQueueId(data.id)
      setListQueueId(String(data.id))
      setQueueMsg({ ok: true, text: `Queue created (id ${data.id}).` })
      loadQueues(activeProjectId)
    } catch (e) {
      setQueueMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }

  const loadStats = useCallback(
    async (qIdOverride) => {
      const qId = qIdOverride || listQueueId || activeQueueId
      if (!qId) return
      try {
        const { data } = await client.get(`/queues/${qId}/stats`)
        setStats(data)
      } catch {
        // stats panel fails silently — jobs table error is the primary signal
      }
    },
    [listQueueId, activeQueueId]
  )

  const loadJobs = useCallback(
    async (qIdOverride) => {
      const qId = qIdOverride || listQueueId || activeQueueId
      if (!qId) {
        setJobsMsg({ ok: false, text: 'Enter a queue id.' })
        return
      }
      try {
        const { data } = await client.get(`/jobs/queue/${qId}`, {
          params: { page: 0, size: 20, sort: 'updatedAt,desc' }
        })
        const list = data.content || data
        setJobs(list)
        setJobsMsg({ ok: true, text: `${list.length} job(s) loaded.` })
        loadStats(qId)
      } catch (e) {
        setJobsMsg({ ok: false, text: e.response?.data?.message || e.message })
      }
    },
    [listQueueId, activeQueueId, loadStats]
  )

  async function submitJob() {
    const qId = jobForm.queueId || activeQueueId
    if (!qId) {
      setJobMsg({ ok: false, text: 'No queue id — create a queue or enter one.' })
      return
    }
    setJobMsg(null)
    try {
      const body = {
        queueId: Number(qId),
        jobType: jobForm.jobType,
        payload: jobForm.payload,
        priority: Number(jobForm.priority)
      }
      if (jobForm.jobType === 'DELAYED') {
        body.delaySeconds = Number(jobForm.delaySeconds)
      } else if (jobForm.jobType === 'SCHEDULED') {
        if (!jobForm.scheduledAt) {
          setJobMsg({ ok: false, text: 'scheduledAt is required for SCHEDULED jobs — pick a date/time.' })
          return
        }
        // datetime-local gives "2026-08-24T14:30" — backend LocalDateTime needs seconds too.
        body.scheduledAt = jobForm.scheduledAt.length === 16 ? jobForm.scheduledAt + ':00' : jobForm.scheduledAt
      }
      const { data } = await client.post('/jobs', body)
      setJobMsg({ ok: true, text: `Job submitted (id ${data.id}, status ${data.status}).` })
      setListQueueId(String(qId))
      loadJobs(qId)
    } catch (e) {
      setJobMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }

  async function loadHistory() {
    if (!historyJobId) return
    try {
      const { data } = await client.get(`/jobs/${historyJobId}/history`)
      setHistory(data)
    } catch (e) {
      setJobsMsg({ ok: false, text: e.response?.data?.message || e.message })
    }
  }

  useEffect(() => {
    if (!autoRefresh) return
    const id = setInterval(() => loadJobs(), 3000)
    return () => clearInterval(id)
  }, [autoRefresh, loadJobs])

  return (
    <div>
      <header>
        <h1>
          job<span>scheduler</span>://console
        </h1>
        <button className="secondary" onClick={logout}>
          Logout
        </button>
      </header>

      <main>
        <section className="panel">
          <h2>Project</h2>
          <label>Your projects</label>
          <select
            value={activeProjectId ?? ''}
            onChange={(e) => selectProject(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">— select a project —</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} (id {p.id})
              </option>
            ))}
          </select>
          <div className="hint" style={{ marginTop: 4 }}>
            {projects.length === 0 ? 'No projects yet.' : `${projects.length} project(s).`}{' '}
            <button
              className="secondary"
              style={{ margin: 0, padding: '2px 8px', fontSize: 11 }}
              onClick={loadProjects}
            >
              refresh
            </button>
          </div>

          <label style={{ marginTop: 16 }}>New project name</label>
          <div className="row">
            <input value={projectName} onChange={(e) => setProjectName(e.target.value)} />
            <button onClick={createProject} style={{ flex: '0 0 auto' }}>
              Create
            </button>
          </div>
          <div className="hint">Active project id: {activeProjectId ?? '—'}</div>
          {projectMsg && <div className={`msg ${projectMsg.ok ? 'ok' : 'err'}`}>{projectMsg.text}</div>}
        </section>

        <section className="panel">
          <h2>Queue</h2>
          <label>Queues in active project</label>
          <select
            value={activeQueueId ?? ''}
            onChange={(e) => {
              const id = e.target.value ? Number(e.target.value) : null
              setActiveQueueId(id)
              setListQueueId(id ? String(id) : '')
            }}
            disabled={!activeProjectId}
          >
            <option value="">{activeProjectId ? '— select a queue —' : 'select a project first'}</option>
            {queues.map((q) => (
              <option key={q.id} value={q.id}>
                {q.name} (id {q.id})
              </option>
            ))}
          </select>
          <div className="hint" style={{ marginTop: 4 }}>
            {activeProjectId
              ? queues.length === 0
                ? 'No queues in this project yet.'
                : `${queues.length} queue(s).`
              : ''}
          </div>

          <label style={{ marginTop: 16 }}>New queue name</label>
          <input value={queueForm.name} onChange={(e) => setQueueForm({ ...queueForm, name: e.target.value })} />
          <div className="row">
            <div>
              <label>Priority</label>
              <input
                value={queueForm.priority}
                onChange={(e) => setQueueForm({ ...queueForm, priority: e.target.value })}
              />
            </div>
            <div>
              <label>Concurrency limit</label>
              <input
                value={queueForm.concurrencyLimit}
                onChange={(e) => setQueueForm({ ...queueForm, concurrencyLimit: e.target.value })}
              />
            </div>
          </div>
          <div className="row">
            <div>
              <label>Retry type</label>
              <select
                value={queueForm.retryType}
                onChange={(e) => setQueueForm({ ...queueForm, retryType: e.target.value })}
              >
                <option>EXPONENTIAL</option>
                <option>LINEAR</option>
                <option>FIXED</option>
              </select>
            </div>
            <div>
              <label>Base delay (s)</label>
              <input
                value={queueForm.baseDelaySeconds}
                onChange={(e) => setQueueForm({ ...queueForm, baseDelaySeconds: e.target.value })}
              />
            </div>
            <div>
              <label>Max retries</label>
              <input
                value={queueForm.maxRetries}
                onChange={(e) => setQueueForm({ ...queueForm, maxRetries: e.target.value })}
              />
            </div>
          </div>
          <button onClick={createQueue}>Create queue</button>
          <div className="hint">Active queue id: {activeQueueId ?? '—'}</div>
          {queueMsg && <div className={`msg ${queueMsg.ok ? 'ok' : 'err'}`}>{queueMsg.text}</div>}
        </section>

        <section className="panel full">
          <h2>Submit job</h2>
          <div className="row">
            <div>
              <label>Queue id</label>
              <input
                value={jobForm.queueId}
                onChange={(e) => setJobForm({ ...jobForm, queueId: e.target.value })}
                placeholder={activeQueueId ? `uses ${activeQueueId} if blank` : ''}
              />
            </div>
            <div>
              <label>Job type</label>
              <select value={jobForm.jobType} onChange={(e) => setJobForm({ ...jobForm, jobType: e.target.value })}>
                <option>IMMEDIATE</option>
                <option>DELAYED</option>
                <option>SCHEDULED</option>
              </select>
            </div>
            <div>
              <label>{jobForm.jobType === 'SCHEDULED' ? 'Scheduled at' : 'Delay (s) — DELAYED only'}</label>
              {jobForm.jobType === 'SCHEDULED' ? (
                <input
                  type="datetime-local"
                  value={jobForm.scheduledAt}
                  onChange={(e) => setJobForm({ ...jobForm, scheduledAt: e.target.value })}
                />
              ) : (
                <input
                  value={jobForm.delaySeconds}
                  onChange={(e) => setJobForm({ ...jobForm, delaySeconds: e.target.value })}
                />
              )}
            </div>
            <div>
              <label>Priority</label>
              <input value={jobForm.priority} onChange={(e) => setJobForm({ ...jobForm, priority: e.target.value })} />
            </div>
          </div>
          <label>Payload (JSON)</label>
          <textarea value={jobForm.payload} onChange={(e) => setJobForm({ ...jobForm, payload: e.target.value })} />
          <button onClick={submitJob}>Submit job</button>
          <div className="msg">{jobMsg && <span className={jobMsg.ok ? 'ok' : 'err'}>{jobMsg.text}</span>}</div>
        </section>

        <section className="panel full">
          <h2>Queue stats</h2>
          <div className="row">
            <input value={listQueueId} onChange={(e) => setListQueueId(e.target.value)} placeholder="queue id" />
            <button onClick={() => loadStats()} style={{ flex: '0 0 auto' }}>
              Refresh
            </button>
          </div>
          {stats && (
            <div className="stats-grid" style={{ marginTop: 14 }}>
              {Object.entries(stats).map(([k, v]) => (
                <div className="stat" key={k}>
                  <div className="n">{v}</div>
                  <div className="l">{k}</div>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="panel full">
          <div className="toolbar">
            <h2 style={{ margin: 0, border: 'none', padding: 0 }}>Jobs</h2>
            <div>
              <input
                value={listQueueId}
                onChange={(e) => setListQueueId(e.target.value)}
                placeholder="queue id"
                style={{ display: 'inline-block', width: 120 }}
              />
              <button className="secondary" onClick={() => loadJobs()}>
                Refresh
              </button>
              <button className="secondary" onClick={() => setAutoRefresh(!autoRefresh)}>
                Auto: {autoRefresh ? 'on (3s)' : 'off'}
              </button>
            </div>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Priority</th>
                  <th>Attempts</th>
                  <th>Updated</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((j) => (
                  <tr key={j.id}>
                    <td>{j.id}</td>
                    <td>{j.jobType}</td>
                    <td>
                      <StatusBadge status={j.status} />
                    </td>
                    <td>{j.priority}</td>
                    <td>{j.attemptCount}</td>
                    <td>{(j.updatedAt || '').replace('T', ' ').substring(0, 19)}</td>
                    <td>
                      <button
                        className="secondary"
                        style={{ margin: 0, padding: '4px 8px', fontSize: 11 }}
                        onClick={() => {
                          setHistoryJobId(String(j.id))
                          loadHistory()
                        }}
                      >
                        history
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="msg">{jobsMsg && <span className={jobsMsg.ok ? 'ok' : 'err'}>{jobsMsg.text}</span>}</div>
        </section>

        <section className="panel full">
          <h2>Job execution history</h2>
          <div className="row">
            <input value={historyJobId} onChange={(e) => setHistoryJobId(e.target.value)} placeholder="job id" />
            <button onClick={loadHistory} style={{ flex: '0 0 auto' }}>
              Load
            </button>
          </div>
          <table style={{ marginTop: 10 }}>
            <thead>
              <tr>
                <th>Attempt</th>
                <th>Status</th>
                <th>Worker</th>
                <th>Started</th>
                <th>Completed</th>
                <th>Error</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h, i) => (
                <tr key={i}>
                  <td>{h.attemptNumber}</td>
                  <td>
                    <StatusBadge status={h.status} />
                  </td>
                  <td>{h.workerId ?? '—'}</td>
                  <td>{(h.startedAt || '').replace('T', ' ').substring(0, 19)}</td>
                  <td>{(h.completedAt || '').replace('T', ' ').substring(0, 19)}</td>
                  <td
                    style={{
                      color: 'var(--failed)',
                      maxWidth: 220,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis'
                    }}
                  >
                    {h.errorMessage || ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </main>
    </div>
  )
}
