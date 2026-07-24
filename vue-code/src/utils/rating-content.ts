export const parseRatingContents = (value?: string): string[] => {
  const normalized = value?.trim()
  if (!normalized) return []
  try {
    const contents = JSON.parse(normalized)
    if (Array.isArray(contents)) {
      return [...new Set(contents.filter(item => typeof item === 'string').map(item => item.trim()).filter(Boolean))]
    }
  } catch {
    // 历史单条文案继续按原内容展示。
  }
  return [normalized]
}

export const serializeRatingContents = (contents: string[]): string =>
  JSON.stringify([...new Set(contents.map(item => item.trim()).filter(Boolean))])
