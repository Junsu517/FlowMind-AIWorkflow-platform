<template>
  <NodeFrame :data="data" :selected="selected" node-type="llm" />
</template>

<script setup>
import NodeFrame from '../NodeFrame.vue'

defineProps({
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false }
})
</script>
