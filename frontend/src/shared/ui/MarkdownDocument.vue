<script setup lang="ts">
import { computed } from 'vue'

type InlinePart = { type: 'text' | 'strong' | 'em' | 'code' | 'link'; text: string; href?: string }
type MarkdownBlock =
  | { type: 'heading'; level: 2 | 3 | 4; content: InlinePart[] }
  | { type: 'paragraph'; content: InlinePart[] }
  | { type: 'quote'; content: InlinePart[] }
  | { type: 'list'; ordered: boolean; items: InlinePart[][] }
  | { type: 'code'; code: string; language: string }
  | { type: 'table'; headers: InlinePart[][]; rows: InlinePart[][][] }

const props = defineProps<{ text: string }>()

function parseInline(value: string): InlinePart[] {
  const parts: InlinePart[] = []
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\(https?:\/\/[^)\s]+\)|\*[^*]+\*)/g
  let cursor = 0
  for (const match of value.matchAll(pattern)) {
    if (match.index > cursor) parts.push({ type: 'text', text: value.slice(cursor, match.index) })
    const token = match[0]
    if (token.startsWith('**')) parts.push({ type: 'strong', text: token.slice(2, -2) })
    else if (token.startsWith('`')) parts.push({ type: 'code', text: token.slice(1, -1) })
    else if (token.startsWith('[')) {
      const labelEnd = token.indexOf('](')
      parts.push({ type: 'link', text: token.slice(1, labelEnd), href: token.slice(labelEnd + 2, -1) })
    } else parts.push({ type: 'em', text: token.slice(1, -1) })
    cursor = match.index + token.length
  }
  if (cursor < value.length) parts.push({ type: 'text', text: value.slice(cursor) })
  return parts.length ? parts : [{ type: 'text', text: value }]
}

function isTableSeparator(line: string) {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line)
}

function splitTableRow(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
}

function isBlockStart(line: string, next?: string) {
  return /^```/.test(line)
    || /^#{1,4}\s+/.test(line)
    || /^>\s?/.test(line)
    || /^[-*]\s+/.test(line)
    || /^\d+[.)]\s+/.test(line)
    || (line.includes('|') && Boolean(next && isTableSeparator(next)))
}

function parseMarkdown(value: string): MarkdownBlock[] {
  const lines = value.replace(/\r\n/g, '\n').split('\n')
  const blocks: MarkdownBlock[] = []
  let index = 0
  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) {
      index += 1
      continue
    }

    if (line.startsWith('```')) {
      const language = line.replace(/^```/, '').trim()
      const code: string[] = []
      index += 1
      while (index < lines.length && !lines[index].startsWith('```')) {
        code.push(lines[index])
        index += 1
      }
      blocks.push({ type: 'code', language, code: code.join('\n') })
      index += 1
      continue
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      const level = Math.min(heading[1].length + 1, 4) as 2 | 3 | 4
      blocks.push({ type: 'heading', level, content: parseInline(heading[2]) })
      index += 1
      continue
    }

    if (line.includes('|') && lines[index + 1] && isTableSeparator(lines[index + 1])) {
      const headers = splitTableRow(line).map(parseInline)
      const rows: InlinePart[][][] = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]).map(parseInline))
        index += 1
      }
      blocks.push({ type: 'table', headers, rows })
      continue
    }

    const unordered = /^[-*]\s+/.test(line)
    const ordered = /^\d+[.)]\s+/.test(line)
    if (unordered || ordered) {
      const items: InlinePart[][] = []
      while (index < lines.length && (unordered ? /^[-*]\s+/.test(lines[index]) : /^\d+[.)]\s+/.test(lines[index]))) {
        items.push(parseInline(lines[index].replace(unordered ? /^[-*]\s+/ : /^\d+[.)]\s+/, '')))
        index += 1
      }
      blocks.push({ type: 'list', ordered, items })
      continue
    }

    if (/^>\s?/.test(line)) {
      const quote: string[] = []
      while (index < lines.length && /^>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^>\s?/, ''))
        index += 1
      }
      blocks.push({ type: 'quote', content: parseInline(quote.join(' ')) })
      continue
    }

    const paragraph = [line.trim()]
    index += 1
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines[index], lines[index + 1])) {
      paragraph.push(lines[index].trim())
      index += 1
    }
    blocks.push({ type: 'paragraph', content: parseInline(paragraph.join(' ')) })
  }
  return blocks
}

const blocks = computed(() => parseMarkdown(props.text))
</script>

<template>
  <article class="markdown-document">
    <template v-for="(block, blockIndex) in blocks" :key="blockIndex">
      <component :is="`h${block.level}`" v-if="block.type === 'heading'" class="doc-heading">
        <template v-for="(part, partIndex) in block.content" :key="partIndex">
          <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
          <em v-else-if="part.type === 'em'">{{ part.text }}</em>
          <code v-else-if="part.type === 'code'">{{ part.text }}</code>
          <a v-else-if="part.type === 'link'" :href="part.href" rel="noreferrer" target="_blank">{{ part.text }}</a>
          <span v-else>{{ part.text }}</span>
        </template>
      </component>

      <p v-else-if="block.type === 'paragraph'">
        <template v-for="(part, partIndex) in block.content" :key="partIndex">
          <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
          <em v-else-if="part.type === 'em'">{{ part.text }}</em>
          <code v-else-if="part.type === 'code'">{{ part.text }}</code>
          <a v-else-if="part.type === 'link'" :href="part.href" rel="noreferrer" target="_blank">{{ part.text }}</a>
          <span v-else>{{ part.text }}</span>
        </template>
      </p>

      <blockquote v-else-if="block.type === 'quote'">
        <template v-for="(part, partIndex) in block.content" :key="partIndex">
          <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
          <em v-else-if="part.type === 'em'">{{ part.text }}</em>
          <code v-else-if="part.type === 'code'">{{ part.text }}</code>
          <a v-else-if="part.type === 'link'" :href="part.href" rel="noreferrer" target="_blank">{{ part.text }}</a>
          <span v-else>{{ part.text }}</span>
        </template>
      </blockquote>

      <component :is="block.ordered ? 'ol' : 'ul'" v-else-if="block.type === 'list'">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <template v-for="(part, partIndex) in item" :key="partIndex">
            <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
            <em v-else-if="part.type === 'em'">{{ part.text }}</em>
            <code v-else-if="part.type === 'code'">{{ part.text }}</code>
            <a v-else-if="part.type === 'link'" :href="part.href" rel="noreferrer" target="_blank">{{ part.text }}</a>
            <span v-else>{{ part.text }}</span>
          </template>
        </li>
      </component>

      <pre v-else-if="block.type === 'code'"><code>{{ block.code }}</code></pre>

      <div v-else-if="block.type === 'table'" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th v-for="(header, headerIndex) in block.headers" :key="headerIndex">
                <template v-for="(part, partIndex) in header" :key="partIndex">
                  <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
                  <code v-else-if="part.type === 'code'">{{ part.text }}</code>
                  <span v-else>{{ part.text }}</span>
                </template>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex">
                <template v-for="(part, partIndex) in cell" :key="partIndex">
                  <strong v-if="part.type === 'strong'">{{ part.text }}</strong>
                  <em v-else-if="part.type === 'em'">{{ part.text }}</em>
                  <code v-else-if="part.type === 'code'">{{ part.text }}</code>
                  <a v-else-if="part.type === 'link'" :href="part.href" rel="noreferrer" target="_blank">{{ part.text }}</a>
                  <span v-else>{{ part.text }}</span>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </article>
</template>

<style scoped>
.markdown-document {
  display: grid;
  gap: 10px;
  color: var(--ink);
  line-height: 1.78;
}

.doc-heading {
  margin: 4px 0 0;
  color: var(--primary-strong);
  font-family: Georgia, "Songti SC", serif;
  line-height: 1.35;
}

h2.doc-heading { font-size: 21px; }
h3.doc-heading { font-size: 18px; }
h4.doc-heading { font-size: 16px; }

p, blockquote, ul, ol, pre {
  margin: 0;
}

ul, ol {
  padding-left: 1.35rem;
}

li + li {
  margin-top: 5px;
}

blockquote {
  padding: 10px 12px;
  border-left: 3px solid var(--primary);
  background: color-mix(in srgb, var(--primary) 6%, var(--surface));
  color: var(--muted);
}

code {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-muted);
  padding: 1px 5px;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: .92em;
}

pre {
  overflow-x: auto;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

pre code {
  border: 0;
  padding: 0;
  background: transparent;
}

a {
  color: var(--primary);
  text-underline-offset: 3px;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th, td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: top;
}

th {
  background: var(--surface-muted);
  color: var(--primary-strong);
}

tr:last-child td {
  border-bottom: 0;
}
</style>
