import divisionData from './china-division.json'

export interface ChinaDivision {
  code: string
  name: string
  gps: string
  children?: ChinaDivision[]
}

export interface PublishAddress {
  province: string
  city: string
  district: string
  divisionId: string
  gps: string
  poiId: string
  poiName: string
}

export const CHINA_DIVISIONS = divisionData as ChinaDivision[]

export const cityDisplayName = (province: ChinaDivision | undefined, city: ChinaDivision) =>
  city.name === '市辖区' ? province?.name || city.name : city.name
