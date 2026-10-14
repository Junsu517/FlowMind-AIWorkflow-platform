<template>
  <router-view v-slot="{ Component }">
    <!--
      注意：这里不能加 mode="out-in"。
      实测（2026-10-15）：out-in 模式下从「工作流编辑器」跳转到别的页面时，
      旧节点leave 的 afterLeave 回调不会触发，BaseTransition 的 state.isLeaving
      永久停在 true，router-view 会一直渲染占位注释节点 —— 表现是整页白屏，
      再点任何导航都不恢复（编辑器页是唯一触发源，其它页面互跳正常）。
      默认模式（新旧同时过渡）没有这个问题，0.2s 淡入淡出观感也不受影响。
    -->
    <transition name="fade">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<script setup></script>
