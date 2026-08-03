const EMOJI_CHARS = [
  '😀', '😁', '😂', '🤣', '😊', '😇', '🙂', '😉', '😍', '😘', '😋', '😜',
  '🤔', '🤨', '😐', '😴', '🥳', '😎', '🤓', '🥺', '😢', '😭', '😤', '😡',
  '🤯', '😱', '😨', '🥶',   '🤗', '🤝', '👋', '👍', '👎', '👏', '🙌', '🙏', '💪',
  '🤞', '✌️', '❤️', '💔', '💖', '💯', '🔥', '✨', '🎉', '🎊', '💡', '📚',
  '🍀', '🌱', '☀️', '🌙', '⭐', '🌈', '🍎', '🍉', '🍰', '☕', '🎵', '🎮',
  '🏆', '🚀',
]

function fileFor(char: string) {
  const codepoints = Array.from(char)
    .map(value => value.codePointAt(0)!)
    .filter(value => value !== 0xfe0f)
    .map(value => value.toString(16))
    .join('-')
  return `${codepoints}.svg`
}

export const EMOJIS = EMOJI_CHARS.map(char => ({ char, file: fileFor(char) }))

const byChar = new Map(EMOJIS.map(emoji => [emoji.char, emoji.file]))

export function emojiFile(char: string) {
  return byChar.get(char) ?? null
}

export const EMOJI_PATTERN = new RegExp(
  EMOJI_CHARS.sort((a, b) => Array.from(b).length - Array.from(a).length).join('|'),
  'gu',
)
