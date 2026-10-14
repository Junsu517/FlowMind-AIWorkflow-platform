/**
 * 新手引导（Driver.js）
 *
 * 三条硬约束（踩过的坑都写在注释里）：
 * 1. 锚点一律用 data-tour 属性，不用 CSS class —— 改版改样式不会把引导链打断。
 * 2. 跨页面步骤必须「结束本页 tour → 路由跳转 → 等目标元素挂载 → 再起下一段」。
 *    SPA 里目标没挂载时 driver.js 会静默失败（既不报错也不显示），这是最大的坑。
 * 3. driver.js 默认是浅色皮肤，必须自己盖一层深色（styles/tour.css），否则像外挂组件。
 *
 * 文案规范：全部第二人称「你」，零技术黑话（不说"参数"，说"你填进去的信息"）。
 */
import { driver } from 'driver.js'
import 'driver.js/dist/driver.css'
import '@/styles/tour.css'
import { getWorkflows } from '@/api/workflow'

const TOUR_KEY = 'fm_tour_done'

export function isTourDone() {
  try {
    return localStorage.getItem(TOUR_KEY) === '1'
  } catch {
    return false
  }
}

export function markTourDone() {
  try {
    localStorage.setItem(TOUR_KEY, '1')
  } catch {
    /* 隐私模式下写不进去就算了，不再骚扰是尽力而为 */
  }
}

/** 7 步正片，跨 3 个页面。anchor 对应模板里的 data-tour 属性 */
const STEPS = [
  {
    page: 'list',
    anchor: 'workflow-concept',
    title: '一个工作流，就是一个 AI 应用',
    desc: '把「填信息 → 查数据 → AI 思考 → 给出结果」这几步串起来，保存之后它就能自己跑完。你在这个页面管理自己做过的所有工作流。'
  },
  {
    page: 'list',
    anchor: 'create-workflow',
    title: '从这里开始',
    desc: '点它新建一个空白工作流。进去以后可以先点「模板」载入现成的例子，照着改一改就能跑。'
  },
  {
    page: 'editor',
    anchor: 'canvas',
    title: '画布：把步骤连成一条线',
    desc: '四种积木：输入 = 你填进去的信息；LLM = AI 大脑；条件 = 按情况走不同的路；HTTP = 去调用外面的服务。拖进来、连上线，就是一条能跑的流程。',
    side: 'top'
  },
  {
    page: 'editor',
    anchor: 'template',
    title: '不知道怎么搭？先用模板',
    desc: '点「模板」可以一键载入天气助手：填城市 → 查天气 → AI 给穿衣建议。先跑通一个现成的，再改成你自己的。'
  },
  {
    page: 'editor',
    anchor: 'run',
    title: '点开它，就能看到效果',
    desc: '这是调试面板。填好信息后点「运行」，这里会实时显示每一步在做什么，AI 的答案会一个字一个字冒出来。'
  },
  {
    page: 'editor',
    anchor: 'publish',
    title: '把它变成一个链接，给别人用',
    desc: '点「发布」会生成一个接口地址。你自己的程序、网页、小程序都能调用这个工作流，不用再打开这个网站。'
  },
  {
    page: 'dashboard',
    anchor: 'dashboard',
    title: '仪表盘：看看你用了多少',
    desc: '跑了多少次、花了多少、成功了多少，都画在这里。随时可以回来看一眼。'
  }
]

const TOTAL = STEPS.length

/** 按页面切成 3 段，段与段之间靠路由跳转衔接 */
const SEGMENTS = [
  { page: 'list', route: '/workflows' },
  {
    page: 'editor',
    route: (ctx) => (ctx.workflowId ? `/workflows/${ctx.workflowId}/edit` : null),
    requires: (ctx) => !!ctx.workflowId
  },
  { page: 'dashboard', route: '/dashboard' }
]

function anchorOf(step) {
  return `[data-tour="${step.anchor}"]`
}

/** 全局进度用字面量写死（driver.js 的 {{current}} 只认当段内的序号） */
function progressText(globalIndex) {
  return `第 ${globalIndex + 1} 步 / 共 ${TOTAL} 步`
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function waitForSelector(sel, timeout = 8000) {
  return new Promise((resolve) => {
    const start = Date.now()
    const tick = () => {
      const el = document.querySelector(sel)
      if (el && el.isConnected) return resolve(true)
      if (Date.now() - start > timeout) return resolve(false)
      setTimeout(tick, 120)
    }
    tick()
  })
}

let active = null

function baseConfig() {
  return {
    showProgress: true,
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '完成',
    allowClose: true, // 点遮罩 / 按 Esc / 点叉 = 跳过
    overlayColor: '#04060c',
    overlayOpacity: 0.68,
    stagePadding: 8,
    stageRadius: 14,
    popoverClass: 'fm-tour-popover',
    skipMissingElement: true, // 锚点没渲染出来就跳过这一步，不要让引导卡死
    waitForElement: 6000,
    smoothScroll: true,
    // 关闭（含走完最后一步）统一在这里收口：写标记 + 真正销毁
    onDestroyStarted: () => {
      markTourDone()
      const d = active
      active = null
      if (d) d.destroy()
    }
  }
}

function buildSteps(page, isFinal) {
  const indexes = STEPS.map((s, i) => ({ s, i })).filter((x) => x.s.page === page)
  return indexes.map(({ s, i }, order) => {
    const isLastOfSegment = order === indexes.length - 1
    return {
      element: anchorOf(s),
      popover: {
        title: s.title,
        description: s.desc,
        side: s.side || 'bottom',
        align: 'start',
        progressText: progressText(i),
        ...(isLastOfSegment ? { doneBtnText: isFinal ? '完成' : '继续' } : {})
      }
    }
  })
}

async function runSegment(index, ctx) {
  const seg = SEGMENTS[index]
  // 整段都跑不了（比如还没有任何工作流，进不了编辑器）就直接跳到下一段
  if (!seg) {
    markTourDone()
    return
  }
  if (seg.requires && !seg.requires(ctx)) {
    await runSegment(index + 1, ctx)
    return
  }

  const route = typeof seg.route === 'function' ? seg.route(ctx) : seg.route
  if (route && ctx.router && ctx.router.currentRoute.value.path !== route) {
    await ctx.router.push(route).catch(() => {})
  }
  /*
   * 关键：SPA 跳转后目标元素还没挂载，必须等它出现再 drive。
   * App.vue 用的是 <transition mode="out-in">，路由 push 完成后旧页面还在离场、
   * 新页面尚未挂载 —— 此时 querySelector 命中的是「旧页面的同名锚点」，
   * 等它一卸载引导就整段消失。所以 push 之后必须先空等一段过渡时间。
   */
  const firstStep = STEPS.find((s) => s.page === seg.page)
  if (firstStep) {
    await sleep(500)
    await waitForSelector(anchorOf(firstStep), 8000)
  }

  const steps = buildSteps(seg.page, index === SEGMENTS.length - 1)
  if (!steps.length) {
    await runSegment(index + 1, ctx)
    return
  }

  // 最后一步的「继续/完成」：结束本段 → 起下一段
  steps[steps.length - 1].popover.onDoneClick = () => {
    const d = active
    active = null
    if (d) d.destroy() // 程序化销毁不会触发 onDestroyStarted，标记留到最后一段再写
    runSegment(index + 1, ctx)
  }

  const d = driver({ ...baseConfig(ctx), steps })
  active = d
  d.drive(0)
}

/**
 * 从任意页面重开引导：统一先回到工作流列表当起点。
 * @param {import('vue-router').Router} router
 * @param {{ workflowId?: number|string }} [opts]
 */
export async function startTour(router, opts = {}) {
  const ctx = { router, workflowId: opts.workflowId ?? null }

  // 编辑器那段需要一个真实的工作流 id；没传就取列表里第一个
  if (!ctx.workflowId) {
    try {
      const data = await getWorkflows({ page: 1, size: 1 })
      ctx.workflowId = data?.items?.[0]?.id ?? null
    } catch {
      ctx.workflowId = null
    }
  }

  markTourDone() // 先落标记：中途刷新/关页面也不会反复弹欢迎卡
  await runSegment(0, ctx)
}

export function stopTour() {
  const d = active
  active = null
  if (d) d.destroy()
}
