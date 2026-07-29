<script setup lang="ts">
import { computed, onMounted } from 'vue'
import {
  CHINA_DIVISIONS,
  cityDisplayName,
  type ChinaDivision,
  type PublishAddress
} from '@/data/publish-address'

const props = defineProps<{ modelValue: PublishAddress }>()
const emit = defineEmits<{ 'update:modelValue': [value: PublishAddress] }>()

const province = computed(() => CHINA_DIVISIONS.find(item => item.name === props.modelValue.province))
const cities = computed(() => province.value?.children || [])
const city = computed(() => cities.value.find(item => cityDisplayName(province.value, item) === props.modelValue.city))
const districts = computed(() => city.value?.children || [])

// 统一回传平台发布所需的区划编码、坐标和地点名称，避免页面只保存展示文本。
const updateAddress = (nextProvince: ChinaDivision, nextCity: ChinaDivision, nextDistrict: ChinaDivision) => {
  emit('update:modelValue', {
    province: nextProvince.name,
    city: cityDisplayName(nextProvince, nextCity),
    district: nextDistrict.name,
    divisionId: nextDistrict.code,
    gps: nextDistrict.gps || nextCity.gps || nextProvince.gps,
    poiId: '',
    poiName: nextDistrict.name
  })
}

const selectProvince = (event: Event) => {
  const nextProvince = CHINA_DIVISIONS.find(item => item.code === (event.target as HTMLSelectElement).value)
  const nextCity = nextProvince?.children?.[0]
  const nextDistrict = nextCity?.children?.[0]
  if (nextProvince && nextCity && nextDistrict) updateAddress(nextProvince, nextCity, nextDistrict)
}

const selectCity = (event: Event) => {
  const nextProvince = province.value
  const nextCity = cities.value.find(item => item.code === (event.target as HTMLSelectElement).value)
  const nextDistrict = nextCity?.children?.[0]
  if (nextProvince && nextCity && nextDistrict) updateAddress(nextProvince, nextCity, nextDistrict)
}

const selectDistrict = (event: Event) => {
  const nextProvince = province.value
  const nextCity = city.value
  const nextDistrict = districts.value.find(item => item.code === (event.target as HTMLSelectElement).value)
  if (nextProvince && nextCity && nextDistrict) updateAddress(nextProvince, nextCity, nextDistrict)
}

onMounted(() => {
  if (props.modelValue.divisionId && props.modelValue.gps) return
  const nextProvince = province.value || CHINA_DIVISIONS[0]
  const nextCity = nextProvince?.children?.[0]
  const nextDistrict = nextCity?.children?.[0]
  if (nextProvince && nextCity && nextDistrict) updateAddress(nextProvince, nextCity, nextDistrict)
})
</script>

<template>
  <label class="workbench__field">
    省份
    <select class="workbench__select" :value="province?.code" @change="selectProvince">
      <option v-for="item in CHINA_DIVISIONS" :key="item.code" :value="item.code">{{ item.name }}</option>
    </select>
  </label>
  <label class="workbench__field">
    城市
    <select class="workbench__select" :value="city?.code" @change="selectCity">
      <option v-for="item in cities" :key="item.code" :value="item.code">{{ cityDisplayName(province, item) }}</option>
    </select>
  </label>
  <label class="workbench__field">
    区县
    <select class="workbench__select" :value="props.modelValue.divisionId" @change="selectDistrict">
      <option v-for="item in districts" :key="item.code" :value="item.code">{{ item.name }}</option>
    </select>
    <small>行政区划编码与定位数据将随发布请求提交，无需额外地图 API Key。</small>
  </label>
</template>
