import { useCallback, useEffect, useState } from 'react'
import {
  ArrowUpRight,
  Check,
  CircleAlert,
  Clock3,
  Copy,
  GitBranch,
  LoaderCircle,
  LogOut,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react'
import './App.css'

async function api(path, { csrf, ...options } = {}) {
  const headers = new Headers(options.headers)
  if (csrf) headers.set(csrf.headerName, csrf.token)

  const response = await fetch(path, {
    credentials: 'same-origin',
    ...options,
    headers,
  })

  if (response.status === 204) return null

  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(payload.detail || payload.message || '请求暂时无法完成，请稍后重试。')
    error.status = response.status
    throw error
  }
  return payload
}

function formatRemaining(expiresAt, now) {
  const seconds = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function Step({ number, title, detail, active, complete }) {
  return (
    <li className={`step ${active ? 'is-active' : ''} ${complete ? 'is-complete' : ''}`}>
      <span className="step-number" aria-hidden="true">
        {complete ? <Check size={15} strokeWidth={2.5} /> : number}
      </span>
      <span className="step-copy">
        <span className="step-title">{title}</span>
        <span className="step-detail">{detail}</span>
      </span>
    </li>
  )
}

function App() {
  const [csrf, setCsrf] = useState(null)
  const [configured, setConfigured] = useState(null)
  const [user, setUser] = useState(null)
  const [flow, setFlow] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [now, setNow] = useState(0)

  const loadCsrf = useCallback(async () => {
    const token = await api('/api/csrf')
    setCsrf(token)
    return token
  }, [])

  useEffect(() => {
    let active = true
    Promise.all([api('/api/csrf'), api('/api/config')])
      .then(async ([token, config]) => {
        if (!active) return
        setCsrf(token)
        setConfigured(config.clientConfigured)
        try {
          const session = await api('/api/session')
          if (active && session.authenticated) setUser(session.user)
        } catch (cause) {
          if (active) setError(cause.message)
        }
      })
      .catch((cause) => active && setError(cause.message))
    return () => {
      active = false
    }
  }, [loadCsrf])

  useEffect(() => {
    if (!flow || flow.state !== 'PENDING' || user) return undefined
    const delay = Math.max(1, flow.retryAfterSeconds || flow.intervalSeconds || 5) * 1000
    const timer = window.setTimeout(async () => {
      try {
        const status = await api(`/api/device-login/${flow.id}`)
        if (status.state === 'AUTHENTICATED') {
          await loadCsrf()
          const session = await api('/api/session')
          setUser(session.user)
          setFlow(null)
          setError('')
        } else {
          setFlow((current) => current?.id === flow.id
            ? { ...current, ...status, retryDelaySeconds: undefined }
            : current)
          setError('')
        }
      } catch (cause) {
        setError(cause.message)
        if (!cause.status || cause.status >= 500) {
          const retryDelaySeconds = Math.min(
            flow.retryDelaySeconds ? flow.retryDelaySeconds * 2 : Math.max(1, flow.intervalSeconds || 5),
            30,
          )
          setFlow((current) => current?.id === flow.id
            ? { ...current, retryAfterSeconds: retryDelaySeconds, retryDelaySeconds }
            : current)
        } else if (cause.status === 404) {
          try {
            const session = await api('/api/session')
            if (session.authenticated) {
              setUser(session.user)
              setFlow(null)
              setError('')
              return
            }
          } catch {
            // Keep the original polling error when the follow-up session check also fails.
          }
          setFlow((current) => current?.id === flow.id ? { ...current, state: 'FAILED' } : current)
        } else {
          setFlow((current) => current?.id === flow.id ? { ...current, state: 'FAILED' } : current)
        }
      }
    }, delay)
    return () => window.clearTimeout(timer)
  }, [flow, loadCsrf, user])

  useEffect(() => {
    if (!flow) return undefined
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [flow])

  async function startLogin() {
    setBusy(true)
    setError('')
    setFlow(null)
    try {
      const token = csrf || await loadCsrf()
      const created = await api('/api/device-login', {
        method: 'POST',
        csrf: token,
      })
      setNow(Date.now())
      setFlow(created)
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  async function copyCode() {
    if (!flow?.userCode) return
    try {
      await navigator.clipboard.writeText(flow.userCode)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {
      setError('复制失败，请手动选择并复制授权码。')
    }
  }

  async function cancelLogin() {
    if (!flow || !csrf) return
    try {
      await api(`/api/device-login/${flow.id}`, { method: 'DELETE', csrf })
      setFlow(null)
      setError('')
    } catch (cause) {
      setError(cause.message)
    }
  }

  async function logout() {
    setBusy(true)
    setError('')
    try {
      const token = csrf || await loadCsrf()
      await api('/api/logout', { method: 'POST', csrf: token })
      setUser(null)
      await loadCsrf()
    } catch (cause) {
      setError(cause.message)
    } finally {
      setBusy(false)
    }
  }

  const isPending = flow?.state === 'PENDING'
  const isDenied = flow?.state === 'DENIED'
  const isExpired = flow?.state === 'EXPIRED'
  const isFailed = flow?.state === 'FAILED'
  const hasFlow = Boolean(flow)
  const expiredLocally = isPending && new Date(flow.expiresAt).getTime() <= now
  const isWaiting = isPending && !expiredLocally
  const isTerminal = isDenied || isExpired || expiredLocally || isFailed

  return (
    <main className="page-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="GitHub Device Flow 登录首页">
          <span className="brand-mark"><GitBranch size={21} strokeWidth={2.1} /></span>
          <span>Device Flow</span>
        </a>
        <span className="topbar-note"><ShieldCheck size={15} /> 授权由 GitHub 处理</span>
      </header>

      <section className="login-workspace" aria-labelledby="page-title">
        <div className="intro-row">
          <div>
            <p className="eyebrow">SPRING SECURITY · GITHUB OAUTH</p>
            <h1 id="page-title">使用 GitHub 登录</h1>
            <p className="intro-copy">在 GitHub 确认这次授权，本站会为当前浏览器建立独立登录会话。</p>
          </div>
          <div className="protocol-mark" aria-hidden="true">Device<br />Authorization</div>
        </div>

        {user ? (
          <section className="signed-in" aria-live="polite">
            <div className="success-mark"><Check size={23} strokeWidth={2.4} /></div>
            <div className="signed-in-copy">
              <p className="state-label">授权已完成</p>
              <h2>欢迎回来，{user.name || user.login}</h2>
              <p>GitHub 身份 <span className="mono">@{user.login}</span> 已映射到本站会话。</p>
            </div>
            {user.avatarUrl && <img className="avatar" src={user.avatarUrl} alt="" />}
            <button className="button button-secondary logout-button" onClick={logout} disabled={busy}>
              <LogOut size={16} /> 退出登录
            </button>
          </section>
        ) : (
          <>
            <ol className="steps" aria-label="GitHub 设备授权步骤">
              <Step number="1" title="复制设备码" detail="本站生成一次性代码" active={!hasFlow} complete={hasFlow} />
              <Step number="2" title="在 GitHub 授权" detail="登录并确认访问权限" active={isWaiting} complete={flow?.state === 'AUTHORIZED' || flow?.state === 'AUTHENTICATED'} />
              <Step number="3" title="返回本站" detail="自动建立浏览器会话" active={flow?.state === 'AUTHENTICATED'} complete={false} />
            </ol>

            <div className={`authorization-panel ${isWaiting ? 'is-waiting' : ''}`}>
              <div className="authorization-main">
                <div className="code-heading">
                  <span className="state-dot" aria-hidden="true" />
                  <span>{isWaiting ? '等待你在 GitHub 完成授权' : isDenied ? '授权已拒绝' : isExpired || expiredLocally ? '设备码已过期' : isFailed ? 'GitHub 暂时无法完成授权' : '准备连接 GitHub'}</span>
                  {isWaiting && <span className="expires"><Clock3 size={14} /> {formatRemaining(flow.expiresAt, now)}</span>}
                </div>

                {isWaiting ? (
                  <>
                    <div className="user-code" aria-label={`设备码 ${flow.userCode}`}>{flow.userCode}</div>
                    <div className="action-row">
                      <button className="button button-primary" onClick={() => window.open(flow.verificationUri, '_blank', 'noopener,noreferrer')}>
                        打开 GitHub 验证页 <ArrowUpRight size={17} />
                      </button>
                      <button className="button button-secondary copy-button" onClick={copyCode} aria-label={copied ? '授权码已复制' : '复制授权码'}>
                        {copied ? <Check size={17} /> : <Copy size={17} />}
                        {copied ? '已复制' : '复制设备码'}
                      </button>
                    </div>
                    <p className="code-hint">在 GitHub 页面输入上方代码并确认。完成后请回到此页，登录状态会自动更新。</p>
                  </>
                ) : isTerminal ? (
                  <div className="terminal-state">
                    <p>{isDenied ? '你取消了 GitHub 授权。重新开始后可以再次尝试。' : isFailed ? '暂时无法向 GitHub 验证授权结果。重新生成设备码后可以再试一次。' : '这组设备码已失效。重新生成后再到 GitHub 输入即可。'}</p>
                    <button className="button button-primary" onClick={startLogin} disabled={busy || !configured}>
                      <RefreshCw size={16} /> 重新开始
                    </button>
                  </div>
                ) : (
                  <div className="ready-state">
                    <p>开始后会生成一组短时设备码。授权只申请读取你的 GitHub 基本资料。</p>
                    <button className="button button-primary" onClick={startLogin} disabled={busy || configured === false || configured === null}>
                      {busy ? <LoaderCircle className="spin" size={17} /> : <GitBranch size={18} />}
                      {configured === false ? '等待配置 GitHub Client ID' : configured === null ? '正在检查配置…' : busy ? '正在连接 GitHub…' : '开始 GitHub 授权'}
                    </button>
                  </div>
                )}
              </div>

              <aside className="status-rail" aria-live="polite">
                <div className="rail-heading">
                  {isWaiting ? <LoaderCircle className="spin" size={17} /> : isTerminal ? <CircleAlert size={17} /> : <ShieldCheck size={17} />}
                  <span>{isWaiting ? '正在等待授权' : isDenied ? '授权未完成' : isExpired || expiredLocally ? '需要新的设备码' : isFailed ? '暂时无法连接 GitHub' : '授权状态'}</span>
                </div>
                <p>{isWaiting ? `每 ${flow.retryAfterSeconds || flow.intervalSeconds || 5} 秒检查一次。你可以先在新标签页完成授权。` : isTerminal ? '原浏览器的本站 Session 未登录；你可以重新生成设备码。' : configured === false ? '服务端尚未配置 GitHub Client ID。配置后刷新页面即可开始。' : error || 'GitHub access token 只保留在服务端，不会发送到浏览器。'}</p>
                {isWaiting && <button className="text-button" onClick={cancelLogin}>取消这次登录</button>}
              </aside>
            </div>
          </>
        )}

        {error && <p className="error-message" role="alert"><CircleAlert size={16} /> {error}</p>}

        <footer className="page-footer">
          <span>权限范围 <strong>read:user</strong></span>
          <span className="footer-divider" />
          <span>本站不会获取仓库访问权限</span>
        </footer>
      </section>

      <p className="legal-note">继续操作即表示你会在 GitHub 上审核并确认授权范围。</p>
    </main>
  )
}

export default App
