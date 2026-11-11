import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts'
import {
  GraphicComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { use } from 'echarts/core'
import { init, graphic } from 'echarts/core'
import type { ComposeOption, ECharts, SetOptionOpts } from 'echarts/core'
import type {
  BarSeriesOption,
  GaugeSeriesOption,
  LineSeriesOption,
  PieSeriesOption,
} from 'echarts/charts'
import type {
  GraphicComponentOption,
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
} from 'echarts/components'

use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  GaugeChart,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
])

export type DashboardChartOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | GaugeSeriesOption
  | GraphicComponentOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>

export { graphic, init }
export type { ECharts, SetOptionOpts }
