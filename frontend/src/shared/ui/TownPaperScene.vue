<script setup lang="ts">
// A paper diorama for the journal; the interactive town keeps its own renderer.
const houses = [
  { x: 180, y: 164, scale: 1.1, roof: '#567865', side: '#799784', front: '#eee6ce' },
  { x: 292, y: 192, scale: .83, roof: '#b66048', side: '#b79c77', front: '#f3e5c8' },
  { x: 114, y: 228, scale: .67, roof: '#bda573', side: '#b29e7d', front: '#f6ecd8' },
]
const trees = [{ x: 115, y: 153, s: .8 }, { x: 317, y: 142, s: .65 }, { x: 352, y: 214, s: .8 }, { x: 216, y: 257, s: .53 }, { x: 77, y: 198, s: .6 }]
</script>
<template>
  <svg class="paper-town" viewBox="0 0 440 330" fill="none" aria-hidden="true">
    <defs><filter id="paper-town-shadow" x="-40%" y="-60%" width="190%" height="240%"><feGaussianBlur stdDeviation="10" /></filter></defs>
    <path d="M68 243 242 161 402 245 220 326Z" fill="#35382c" opacity=".16" filter="url(#paper-town-shadow)" />
    <path d="M48 224 217 137 389 224 218 311Z" fill="#e0dccc" />
    <path d="M48 224 218 309V299L48 214Z" fill="#c6c0ae" />
    <path d="M218 299 389 214V224L218 309Z" fill="#aea995" />
    <path d="M48 214 217 128 389 214 218 300Z" fill="#d5ddbf" />
    <path d="m67 217 146-74 149 73-143 73Z" stroke="#eff0df" />
    <path d="m127 180 168 82 19-10-168-82Z" fill="#f3e9ce" />
    <path d="m121 251 175-88 19 9-175 89Z" fill="#f3e9ce" />
    <path d="m167 159 81 40 46-22-78-41Z" fill="#859878" opacity=".2" />
    <g v-for="house in houses" :key="house.x" :transform="`translate(${house.x} ${house.y}) scale(${house.scale})`">
      <path d="m-30 18 53-26 58 29-52 27Z" fill="#49583b" opacity=".16" />
      <path d="M-32-34 7-14V33L-32 13Z" :fill="house.front" />
      <path d="M7-14 42-32V15L7 33Z" :fill="house.side" />
      <path d="M-32-34-12-72 7-14Z" fill="#f6eedc" />
      <path d="M-12-72 22-88 49-34 7-12Z" :fill="house.roof" />
      <path d="m-39-33 26-45 5 3-25 46Z" :fill="house.roof" />
      <path d="m-12-72 34-16" stroke="#fff" stroke-opacity=".35" stroke-width="2" />
      <path d="M22-78V-99L30-103 37-99V-73L30-69Z" fill="#d2bea0" />
      <path d="m22-99 8 4 7-4-7-4Z" fill="#eee1c6" />
      <path d="M-25-23-14-17V-3L-25-9Z" fill="#4d6255" /><path d="m-23-20 7 4v10l-7-4Z" fill="#e9c36b" />
      <path d="M-7 3 2 8V29L-7 24Z" fill="#75624b" />
      <path d="m15-7 9-5V1l-9 5ZM29-14l8-4v13l-8 4Z" fill="#eac879" />
      <path d="m-36 13 43 23 40-20" stroke="#ede4cf" stroke-width="3" />
    </g>
    <g v-for="tree in trees" :key="tree.x" :transform="`translate(${tree.x} ${tree.y}) scale(${tree.s})`">
      <ellipse cx="12" cy="8" rx="26" ry="10" fill="#596547" opacity=".16" />
      <path d="M-2-21V9L3 11V-21" fill="#887255" />
      <path d="M0-85-24-26 0-15 23-27Z" fill="#6b8662" /><path d="M0-85V-15L23-27Z" fill="#486d55" /><path d="M0-65-28-9 0 4 27-10Z" fill="#77946d" /><path d="M0-65V4L27-10Z" fill="#557a59" />
    </g>
    <path d="m284 249 28-14m-27 7v15m13-22v15m13-22v15" stroke="#faf2de" stroke-width="3" />
    <ellipse cx="187" cy="253" rx="9" ry="4" fill="#82916d" opacity=".4" /><circle cx="184" cy="234" r="4" fill="#d5b494" /><path d="m180 240 8 1 1 10-10-1Z" fill="#bd422e" /><path d="m181 250-1 6m6-5 1 6" stroke="#4b4d40" stroke-width="2" />
  </svg>
</template>
<style scoped>
.paper-town { width: 100%; height: auto; display: block; overflow: visible; }
</style>
