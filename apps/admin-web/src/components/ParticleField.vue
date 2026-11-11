<script setup lang=ts>
import { onBeforeUnmount, onMounted, ref } from 'vue'

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
  alpha: number
  life: number
  maxLife: number
  spark: boolean
  depth: number
  previousX: number
  previousY: number
}

const canvasRef = ref<HTMLCanvasElement | null>(null)
let context: CanvasRenderingContext2D | null = null
let frame = 0
let resizeObserver: ResizeObserver | null = null
let particles: Particle[] = []
let width = 0
let height = 0
let lastPointerAt = 0
let lastFrame = 0
let pulse = 0
let running = false
const pointer = { x: -1000, y: -1000, active: false }

function createAmbient(): Particle {
  return {
    x: Math.random() * width,
    y: Math.random() * height,
    vx: (Math.random() - 0.5) * 0.18,
    vy: (Math.random() - 0.5) * 0.18,
    radius: 0.75 + Math.random() * 1.75,
    alpha: 0.17 + Math.random() * 0.35,
    life: Infinity,
    maxLife: Infinity,
    spark: false,
    depth: 0.25 + Math.random() * 0.75,
    previousX: 0,
    previousY: 0,
  }
}

function resetAmbient() {
  const count = Math.min(205, Math.max(104, Math.round((width * height) / 7000)))
  particles = Array.from({ length: count }, createAmbient)
  particles.forEach((particle) => { particle.previousX = particle.x; particle.previousY = particle.y })
}

function resize() {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const dpr = Math.min(window.devicePixelRatio || 1, 1.75)
  width = Math.max(1, rect.width)
  height = Math.max(1, rect.height)
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  context = canvas.getContext('2d')
  context?.setTransform(dpr, 0, 0, dpr, 0, 0)
  resetAmbient()
}

function spawnSparks(x: number, y: number, speed: number) {
  const amount = Math.min(6, 1 + Math.round(speed / 18))
  for (let index = 0; index < amount; index += 1) {
    const angle = Math.random() * Math.PI * 2
    const velocity = 0.32 + Math.random() * 1.05 + speed * 0.018
    const life = 30 + Math.random() * 48
    particles.push({
      x: x + (Math.random() - 0.5) * 12,
      y: y + (Math.random() - 0.5) * 12,
      vx: Math.cos(angle) * velocity,
      vy: Math.sin(angle) * velocity,
      radius: 0.9 + Math.random() * 2.2,
      alpha: 0.75 + Math.random() * 0.25,
      life,
      maxLife: life,
      spark: true,
      depth: 1,
      previousX: x,
      previousY: y,
    })
  }
  if (particles.length > 245) particles.splice(205, particles.length - 245)
}

function onPointerMove(event: PointerEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) {
    pointer.active = false
    return
  }
  const x = event.clientX - rect.left
  const y = event.clientY - rect.top
  const distance = Math.hypot(x - pointer.x, y - pointer.y)
  pointer.x = x
  pointer.y = y
  pointer.active = true
  pulse = Math.min(1, pulse + 0.22)
  const now = performance.now()
  if (now - lastPointerAt > 30) {
    spawnSparks(x, y, distance)
    lastPointerAt = now
  }
}

function draw() {
  if (!context) return
  const now = performance.now()
  const delta = Math.min(32, now - (lastFrame || now))
  lastFrame = now
  pulse *= 0.965
  context.clearRect(0, 0, width, height)
  const dark = document.documentElement.classList.contains('dark')
  const color = dark ? '112, 214, 250' : '22, 91, 172'

  for (let index = particles.length - 1; index >= 0; index -= 1) {
    const particle = particles[index]
    particle.previousX = particle.x
    particle.previousY = particle.y
    if (particle.spark) {
      particle.life -= 1
      if (particle.life <= 0) {
        particles.splice(index, 1)
        continue
      }
      particle.vx *= 0.965
      particle.vy *= 0.965
    } else if (pointer.active) {
      const dx = pointer.x - particle.x
      const dy = pointer.y - particle.y
      const distance = Math.hypot(dx, dy)
      if (distance < 220 && distance > 1) {
        const force = (1 - distance / 220) * 0.028 * particle.depth
        particle.vx += (dx / distance) * force
        particle.vy += (dy / distance) * force
        if (distance < 72) {
          particle.vx -= (dx / distance) * 0.035
          particle.vy -= (dy / distance) * 0.035
        }
      }
    }

    particle.x += particle.vx * (delta / 16)
    particle.y += particle.vy * (delta / 16)
    if (!particle.spark) {
      particle.vx *= 0.992
      particle.vy *= 0.992
      if (particle.x < -8) particle.x = width + 8
      if (particle.x > width + 8) particle.x = -8
      if (particle.y < -8) particle.y = height + 8
      if (particle.y > height + 8) particle.y = -8
    }

    const fade = particle.spark ? particle.life / particle.maxLife : 1
    if (particle.spark && fade > 0.05) {
      context.beginPath()
      context.moveTo(particle.previousX, particle.previousY)
      context.lineTo(particle.x, particle.y)
      context.strokeStyle = `rgba(${color}, ${particle.alpha * fade * 0.3})`
      context.lineWidth = particle.radius * 0.7
      context.stroke()
    }
    context.beginPath()
    context.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2)
    context.fillStyle = `rgba(${color}, ${particle.alpha * fade})`
    context.fill()

    if (pointer.active) {
      const distance = Math.hypot(pointer.x - particle.x, pointer.y - particle.y)
      if (distance < 135) {
        context.beginPath()
        context.moveTo(particle.x, particle.y)
        context.lineTo(pointer.x, pointer.y)
        context.strokeStyle = `rgba(${color}, ${(1 - distance / 135) * 0.24 * fade * particle.depth})`
        context.lineWidth = 0.55 + particle.depth * 0.45
        context.stroke()
      }
    }
  }
  if (pointer.active && pulse > 0.02) {
    context.beginPath()
    context.arc(pointer.x, pointer.y, 26 + (1 - pulse) * 70, 0, Math.PI * 2)
    context.strokeStyle = `rgba(${color}, ${pulse * 0.32})`
    context.lineWidth = 1
    context.stroke()
    context.beginPath()
    context.arc(pointer.x, pointer.y, 3 + pulse * 5, 0, Math.PI * 2)
    context.fillStyle = `rgba(${color}, ${pulse * 0.38})`
    context.fill()
  }
  if (running) frame = requestAnimationFrame(draw)
}

function stop() {
  running = false
  cancelAnimationFrame(frame)
}

function start() {
  if (running) return
  running = true
  frame = requestAnimationFrame(draw)
}

function onPointerLeave() {
  pointer.active = false
}

function onVisibilityChange() {
  if (document.hidden) stop()
  else start()
}

onMounted(() => {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  resizeObserver = new ResizeObserver(resize)
  if (canvasRef.value) resizeObserver.observe(canvasRef.value)
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerleave', onPointerLeave)
  document.addEventListener('visibilitychange', onVisibilityChange)
  resize()
  start()
})

onBeforeUnmount(() => {
  stop()
  resizeObserver?.disconnect()
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerleave', onPointerLeave)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template><canvas ref=canvasRef class=particle-field aria-hidden=true /></template>

<style scoped>
.particle-field { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
</style>
