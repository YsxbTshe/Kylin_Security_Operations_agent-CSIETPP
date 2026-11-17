<script setup lang="ts">
import { TooltipProvider } from 'reka-ui'
import { ToastHost, ConfirmHost } from './ui'
</script>

<template>
  <TooltipProvider :delay-duration="200">
    <RouterView />
    <ToastHost />
    <ConfirmHost />
  </TooltipProvider>
</template>
