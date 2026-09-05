<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
</script>

<template>
  <div class="growth-scene" :class="{ compact }" aria-hidden="true">
    <svg viewBox="0 0 560 360" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="420" cy="83" r="37" fill="#EBC76B" />
      <path d="M0 222Q95 147 210 212T560 197V360H0Z" fill="#355D45" />
      <path d="M0 265Q155 202 300 254T560 235V360H0Z" fill="#4B7150" />
      <path d="M284 278L337 278L418 360H175Z" fill="#B7B690" />
      <path d="M0 332H560" stroke="#7D9564" stroke-width="4" />
      <path d="M85 250V156M77 251H94" stroke="#B9B89A" stroke-width="6" />
      <path d="M71 155H99L94 126H76Z" fill="#EBC76B" />
      <path d="M424 283V221M412 252H437" stroke="#284934" stroke-width="6" />
      <path d="M421 164L383 231H460L421 164Z" fill="#8CA06A" />
      <path d="M459 219L432 266H486L459 219Z" fill="#ADC18A" />
      <path d="M159 161H340V280H159Z" fill="#E4D5AB" />
      <path d="M147 156L250 87L351 156V170H147Z" fill="#B87850" />
      <path d="M158 155L250 96L341 155" stroke="#E3AF73" stroke-width="7" />
      <path d="M298 91H315V127H298Z" fill="#D9C59B" />
      <path d="M173 168H329V179H173Z" fill="#C3B88E" />
      <path d="M187 190H221V228H187Z" fill="#345946" />
      <path d="M192 195H216V223H192Z" fill="#EBC76B" />
      <path d="M204 195V223M192 210H216" stroke="#697B51" stroke-width="3" />
      <path d="M277 190H312V228H277Z" fill="#345946" />
      <path d="M282 195H307V223H282Z" fill="#EBC76B" />
      <path d="M294 195V223M282 210H307" stroke="#697B51" stroke-width="3" />
      <path d="M237 216Q237 200 250 200Q263 200 263 216V280H237Z" fill="#365C45" />
      <circle cx="255" cy="249" r="2" fill="#EBC76B" />
      <path d="M146 280H350V286H146Z" fill="#B9B08B" />
      <path d="M127 238H153V273H127Z" fill="#AA7952" />
      <circle cx="139" cy="226" r="24" fill="#78925C" />
      <circle cx="127" cy="230" r="14" fill="#95AA72" />
      <path d="M353 267H382V279H353Z" fill="#B07850" />
      <path d="M359 268V247M370 268V241M378 268V250" stroke="#B4BF7B" stroke-width="4" />
      <circle cx="359" cy="247" r="5" fill="#EBC76B" /><circle cx="370" cy="241" r="5" fill="#D59B72" />
      <path d="M64 299H140M69 288V314M91 288V314M114 288V314M136 288V314" stroke="#B7C094" stroke-width="5" />
      <path d="M395 312H501M400 301V327M423 301V327M447 301V327M471 301V327M495 301V327" stroke="#B7C094" stroke-width="5" />
      <ellipse cx="321" cy="307" rx="17" ry="5" fill="#35523C" opacity=".4" />
      <path d="M310 300V287L315 279L320 286L328 279L332 289V300Q321 313 310 300Z" fill="#E7CAA0" />
      <path d="M331 299Q345 300 341 288" stroke="#E7CAA0" stroke-width="5" stroke-linecap="round" />
      <circle cx="317" cy="292" r="1.5" fill="#36513A" /><circle cx="326" cy="292" r="1.5" fill="#36513A" />
      <path d="M96 80H135M111 64V96M365 56H379M372 49V63" stroke="#ADC098" stroke-width="2" stroke-linecap="round" />
      <circle cx="184" cy="69" r="2" fill="#ADC098" /><circle cx="468" cy="148" r="2" fill="#ADC098" />
    </svg>
  </div>
</template>

<style scoped>
.growth-scene { position: relative; overflow: hidden; background: var(--forest); isolation: isolate; }
.growth-scene svg { display: block; width: 100%; height: 100%; object-fit: cover; }
.compact svg { max-height: 250px; }
</style>
