export type PasswordStrength = 'empty' | 'weak' | 'medium' | 'strong'

export interface RegistrationPasswordEvaluation {
  valid: boolean
  strength: PasswordStrength
  lengthValid: boolean
  categoriesValid: boolean
  usernameValid: boolean
  simpleValid: boolean
  categoryCount: number
}

const commonPasswords = new Set([
  '12345678', 'password', 'password123', 'admin123',
  'qwerty123', 'abc12345', '11111111', '00000000'
])

export function evaluateRegistrationPassword(
  password: string,
  username: string
): RegistrationPasswordEvaluation {
  const lengthValid = password.length >= 8 && password.length <= 72
  const hasLetter = /[A-Za-z]/.test(password)
  const hasDigit = /[0-9]/.test(password)
  const hasSymbol = /[^A-Za-z0-9]/.test(password)
  const categoryCount = Number(hasLetter) + Number(hasDigit) + Number(hasSymbol)
  const categoriesValid = categoryCount >= 2
  const normalizedPassword = password.toLowerCase()
  const normalizedUsername = username.trim().toLowerCase()
  const usernameValid = !normalizedUsername || normalizedPassword !== normalizedUsername
  const simpleValid = !commonPasswords.has(normalizedPassword) && !/^(.)\1+$/.test(password)
  const valid = lengthValid && categoriesValid && usernameValid && simpleValid
  const strength: PasswordStrength = !password
    ? 'empty'
    : !valid
      ? 'weak'
      : password.length >= 12 && categoryCount === 3
        ? 'strong'
        : 'medium'

  return {
    valid,
    strength,
    lengthValid,
    categoriesValid,
    usernameValid,
    simpleValid,
    categoryCount
  }
}
