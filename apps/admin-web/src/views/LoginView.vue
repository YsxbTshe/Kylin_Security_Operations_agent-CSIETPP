<script setup lang=ts>
import { reactive, ref } from 'vue'
import { Activity, ArrowRight, Check, LockKeyhole, Server, ShieldCheck } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { extractErrorMessage } from '../api/http'
import ThemeToggleButton from '../components/ThemeToggleButton.vue'
import ParticleField from '../components/ParticleField.vue'
import { useAuthStore } from '../stores/auth'
import { Alert, Button, FormField, Input } from '../ui'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })
async function handleSubmit() {
  loading.value = true
  error.value = ''
  try {
    await authStore.signIn({ ...form })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/agent-runs'
    await router.push(authStore.mustChangePassword ? '/change-password' : redirect)
  } catch (err) {
    error.value = extractErrorMessage(err, '登录失败，请检查账号信息后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class=login-page>
    <ParticleField />
    <header class=topbar>
      <a class=wordmark href=/>
        <span class=wordmark__symbol><ShieldCheck /></span>
        <b>麒麟安全运维</b>
      </a>
      <div class=topbar__right>
        <span class=system-state><i /> 服务状态正常</span>
        <ThemeToggleButton text />
      </div>
    </header>
    <section class=stage>
      <div class=story>
        <div class=story__content>
        <div class=story__status>
          <span class=pulse><i /><i /><i /></span>
          KYLIN SECURE OPERATIONS
        </div>
        <h1>让每一次系统操作<br />都有边界，也有依据</h1>
        <p class=story__lead>面向麒麟操作系统的安全智能运维平台。连接主机、策略与人员，让自动化始终运行在可见、可控、可追溯的边界内</p>
        <div class=control-flow aria-label=安全执行链>
          <div class=control-flow__head><span>安全执行链</span><small><i /> 当前策略已启用</small></div>
          <div class=control-flow__steps>
            <div><b>01</b><span>感知系统状态</span></div>
            <div><b>02</b><span>校验操作策略</span></div>
            <div><b>03</b><span>审批与受控执行</span></div>
            <div><b>04</b><span>生成审计凭证</span></div>
          </div>
        </div>
        <div class=capabilities>
          <div class=capability><Server /><span><small>主机状态</small><strong>持续感知</strong></span></div>
          <div class=capability><LockKeyhole /><span><small>风险操作</small><strong>人工审批</strong></span></div>
          <div class=capability><Activity /><span><small>执行过程</small><strong>全链审计</strong></span></div>
        </div>
        <div class=story__foot>CONTROL PLANE · POLICY GUARD · AUDIT CHAIN</div>
        </div>
      </div>
      <div class=login-zone>
        <div class=login-card>
          <div class=login-card__head><span>身份验证</span><small><Check /> 加密连接</small></div>
          <h2>登录控制台</h2>
          <p>使用组织分配的账号继续</p>
          <Alert v-if=error tone=danger :title=error class=login-error />
          <form class=login-form @submit.prevent=handleSubmit>
            <FormField label=用户名>
              <Input v-model=form.username autocomplete=username placeholder=输入用户名 />
            </FormField>
            <FormField label=密码>
              <Input v-model=form.password type=password show-password autocomplete=current-password placeholder=输入密码 />
            </FormField>
            <Button type=submit variant=primary block :loading=loading>进入平台 <ArrowRight /></Button>
          </form>
          <div class=login-card__note>登录行为将被记录到组织审计链中</div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page{position:relative;min-height:100vh;overflow:hidden;background:var(--bg);isolation:isolate}.login-page>.particle-field{position:fixed;z-index:0;pointer-events:none}
.topbar{position:relative;z-index:1;height:72px;display:flex;align-items:center;justify-content:space-between;padding:0 5.5vw;border-bottom:1px solid var(--border);background:color-mix(in oklch,var(--surface),transparent 48%)}
.wordmark{display:inline-flex;align-items:center;gap:12px}.wordmark b{font-size:15px;font-weight:650;letter-spacing:.02em}
.wordmark__symbol{width:34px;height:34px;display:grid;place-items:center;background:var(--text);color:var(--surface);border-radius:8px}.wordmark__symbol :deep(svg){width:19px}
.topbar__right{display:flex;align-items:center;gap:20px}.system-state{display:inline-flex;align-items:center;gap:8px;color:var(--text-2);font-size:12px}.system-state i{width:6px;height:6px;border-radius:50%;background:var(--ok);box-shadow:0 0 0 4px var(--ok-soft)}
.stage{min-height:calc(100vh - 72px);display:grid;grid-template-columns:minmax(0,1.18fr) minmax(430px,.82fr)}
.story{position:relative;z-index:1;display:flex;flex-direction:column;justify-content:center;padding:5.5vh 5.5vw 5vh;border-right:1px solid var(--border);overflow:hidden;isolation:isolate;background:color-mix(in oklch,var(--bg),transparent 58%)}
.story__content{position:relative;z-index:1}
.story::after{content:'';position:absolute;width:42%;height:1px;right:0;top:22%;background:linear-gradient(90deg,transparent,var(--border-2))}
.story__status{display:flex;align-items:center;gap:12px;margin-bottom:28px;color:var(--accent);font:600 11px/1 var(--font-mono);letter-spacing:.08em}.pulse{display:flex;gap:3px;align-items:end;height:13px}.pulse i{display:block;width:2px;background:currentColor}.pulse i:nth-child(1){height:5px}.pulse i:nth-child(2){height:13px}.pulse i:nth-child(3){height:8px}
.story h1{max-width:760px;font-family:'Noto Sans SC','Microsoft YaHei UI',sans-serif;font-size:clamp(34px,3.55vw,54px);line-height:1.26;letter-spacing:.035em;font-weight:700;text-wrap:balance}.story__lead{max-width:600px;margin-top:32px;color:var(--text-2);font-size:15px;line-height:1.85;font-weight:430;text-wrap:pretty}
.control-flow{max-width:680px;margin-top:34px;border:1px solid var(--border);border-radius:10px;background:var(--surface)}.control-flow__head{height:42px;display:flex;align-items:center;justify-content:space-between;padding:0 15px;border-bottom:1px solid var(--border);font-size:12px;font-weight:650}.control-flow__head small{display:flex;align-items:center;gap:7px;color:var(--text-3);font-size:10px;font-weight:500}.control-flow__head i{width:6px;height:6px;border-radius:50%;background:var(--ok)}
.control-flow__steps{display:grid;grid-template-columns:repeat(4,1fr)}.control-flow__steps>div{min-width:0;padding:14px 15px;border-right:1px solid var(--border)}.control-flow__steps>div:last-child{border:0}.control-flow__steps b{display:block;margin-bottom:6px;color:var(--accent);font:600 10px var(--font-mono)}.control-flow__steps span{display:block;color:var(--text-2);font-size:11px;white-space:nowrap}
.capabilities{max-width:680px;display:grid;grid-template-columns:repeat(3,1fr);margin-top:24px;border-block:1px solid var(--border)}.capability{min-width:0;display:flex;align-items:center;gap:12px;padding:16px 18px;border-right:1px solid var(--border)}.capability:first-child{padding-left:0}.capability:last-child{border:0}.capability :deep(svg){width:17px;color:var(--accent)}.capability span{display:flex;flex-direction:column;gap:3px}.capability small{color:var(--text-3);font-size:10px}.capability strong{font-size:12px;font-weight:650}.story__foot{margin-top:18px;color:var(--text-3);font:9px var(--font-mono);letter-spacing:.08em}
.login-zone{position:relative;z-index:1;display:flex;align-items:center;justify-content:flex-start;padding:56px clamp(48px,6vw,96px);background:color-mix(in oklch,var(--surface),transparent 52%)}.login-card{width:100%;max-width:430px;padding:34px 30px;border:1px solid color-mix(in oklch,var(--border),transparent 20%);border-radius:14px;background:color-mix(in oklch,var(--surface),transparent 12%);box-shadow:0 12px 34px oklch(0.18 0.03 240/.08)}.login-card__head{display:flex;align-items:center;justify-content:space-between;margin-bottom:36px;padding-bottom:14px;border-bottom:1px solid var(--border)}.login-card__head>span{color:var(--accent);font:600 11px var(--font-mono);letter-spacing:.06em}.login-card__head small{display:flex;align-items:center;gap:5px;color:var(--text-3);font-size:11px}.login-card__head :deep(svg){width:13px;color:var(--ok)}
.login-card h2{font-size:27px;line-height:1.2;letter-spacing:-.025em;font-weight:650}.login-card>p{margin-top:9px;color:var(--text-3);font-size:13px}.login-error{margin-top:22px}.login-form{display:flex;flex-direction:column;gap:19px;margin-top:34px}.login-form :deep(.field){min-height:46px;padding:0 14px;background:var(--bg)}.login-form :deep(.btn){height:46px;margin-top:5px;padding-inline:17px}.login-form :deep(.lb){width:100%;height:100%;justify-content:space-between}.login-form :deep(.btn svg){width:17px;height:17px}.login-card__note{margin-top:24px;padding-top:18px;border-top:1px solid var(--border);color:var(--text-3);font-size:11px}
@media(max-width:1050px){.stage{grid-template-columns:minmax(0,1fr) 430px}.control-flow__steps{grid-template-columns:repeat(2,1fr)}.control-flow__steps>div:nth-child(2){border-right:0}.control-flow__steps>div:nth-child(-n+2){border-bottom:1px solid var(--border)}}
@media(max-width:860px){.stage{grid-template-columns:1fr}.story{min-height:620px;border-right:0;border-bottom:1px solid var(--border)}.login-zone{min-height:560px;justify-content:center}.story h1{font-size:38px}}
@media(max-width:560px){.topbar{padding:0 20px}.system-state{display:none}.story{min-height:auto;padding:58px 24px 42px}.story h1{font-size:31px;line-height:1.3}.story__lead{font-size:14px}.control-flow__steps{grid-template-columns:1fr}.control-flow__steps>div{border-right:0;border-bottom:1px solid var(--border)!important}.control-flow__steps>div:last-child{border:0!important}.capabilities{grid-template-columns:1fr}.capability,.capability:first-child{padding:14px 0;border-right:0;border-bottom:1px solid var(--border)}.capability:last-child{border-bottom:0}.story__foot{display:none}.login-zone{min-height:auto;padding:60px 24px}}
</style>
