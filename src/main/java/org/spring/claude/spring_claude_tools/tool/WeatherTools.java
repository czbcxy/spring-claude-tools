package org.spring.claude.spring_claude_tools.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具，基于国内中国天气网数据镜像（t.weather.itboy.net，无需 API Key）。
 * 通过 Spring AI 的 @Tool 注解暴露给大模型进行 function calling。
 *
 * <p>说明：原实现使用 Open-Meteo（境外接口），在国内网络环境下无法直连（TLS 握手超时）。
 * 现改为国内站点，HTTP 直连可用。</p>
 */
@Component
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    /** 国内天气数据接口：/api/weather/city/{cityCode} */
    private static final String WEATHER_API = "http://t.weather.itboy.net/api/weather/city/{code}";

    private final RestClient restClient = RestClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .build();

    /**
     * 常用城市 → 中国天气网城市编码。
     * 说明：编码规则为 101 + 省份码 + 城市码 + 区县码，这里内置常用城市与主要城市。
     * 若查询城市不在表中，会给出友好提示。
     */
    private static final Map<String, String> CITY_CODES = Map.<String, String>ofEntries(
            // 直辖市
            Map.entry("北京", "101010100"), Map.entry("上海", "101020100"),
            Map.entry("天津", "101030100"), Map.entry("重庆", "101040100"),
            // 省会及主要城市
            Map.entry("西安", "101110101"), Map.entry("杭州", "101210101"),
            Map.entry("南京", "101190101"), Map.entry("武汉", "101200101"),
            Map.entry("成都", "101270101"), Map.entry("广州", "101280101"),
            Map.entry("深圳", "101280601"), Map.entry("长沙", "101250101"),
            Map.entry("郑州", "101180101"), Map.entry("济南", "101120101"),
            Map.entry("青岛", "101120201"), Map.entry("沈阳", "101070101"),
            Map.entry("大连", "101070201"), Map.entry("哈尔滨", "101050101"),
            Map.entry("长春", "101060101"), Map.entry("石家庄", "101090101"),
            Map.entry("太原", "101100101"), Map.entry("合肥", "101220101"),
            Map.entry("福州", "101230101"), Map.entry("厦门", "101230201"),
            Map.entry("南昌", "101240101"), Map.entry("南宁", "101300101"),
            Map.entry("昆明", "101290101"), Map.entry("贵阳", "101260101"),
            Map.entry("海口", "101310101"), Map.entry("兰州", "101160101"),
            Map.entry("西宁", "101150101"), Map.entry("银川", "101170101"),
            Map.entry("呼和浩特", "101080101"), Map.entry("乌鲁木齐", "101130101"),
            Map.entry("拉萨", "101140101"), Map.entry("苏州", "101190401"),
            Map.entry("无锡", "101190201"), Map.entry("宁波", "101210401"),
            Map.entry("温州", "101210701"), Map.entry("东莞", "101281601"),
            Map.entry("佛山", "101280800"), Map.entry("珠海", "101280701"),
            Map.entry("洛阳", "101180901"), Map.entry("烟台", "101120501"),
            Map.entry("徐州", "101190801"), Map.entry("常州", "101191101")
    );

    @Tool(description = "查询指定城市的当前天气情况，返回气温、体感温度、湿度、风速和天气现象。当用户询问天气时调用此工具")
    public String getCurrentWeather(@ToolParam(description = "城市名称，例如：北京、上海、Guangzhou") String city) {
        log.info("开始调用工具查询当前天气:{}", city);

        String code = resolveCityCode(city);
        if (code == null) {
            return "未找到城市：" + city + "（当前仅内置国内主要城市，可尝试周边大城市或省会）";
        }

        WeatherResp resp;
        try {
            resp = restClient.get()
                    .uri(WEATHER_API, code)
                    .retrieve()
                    .body(WeatherResp.class);
        } catch (Exception e) {
            log.error("查询天气失败: {}", city, e);
            return "天气查询失败：" + city + "，原因：" + e.getMessage();
        }

        if (resp == null || resp.data() == null) {
            return "天气查询失败：" + city;
        }

        WeatherData data = resp.data();
        String cityName = resp.cityInfo() == null ? city : resp.cityInfo().city();

        return """
                城市：%s（更新时间 %s）
                气温：%s°C
                湿度：%s
                风向风力：%s %s
                空气质量：%s（AQI %s，PM2.5 %s）
                感冒提示：%s
                """.formatted(
                cityName,
                resp.time() == null ? "-" : resp.time(),
                nvl(data.wendu()),
                nvl(data.shidu()),
                nvl(data.quality()), nvl(data.pm25()),
                nvl(data.ganmao()));
    }

    @Tool(description = "查询指定城市未来多天的天气预报（每日最高/最低气温、降水概率、降水量、天气现象、最大风速）。当用户询问未来几天的天气、明天/周末的天气时调用此工具")
    public String getWeatherForecast(
            @ToolParam(description = "城市名称，例如：北京、上海、Guangzhou") String city,
            @ToolParam(description = "预报天数，范围 1-16，默认 7") int days) {

        int forecastDays = Math.max(1, Math.min(days, 16));
        String code = resolveCityCode(city);
        if (code == null) {
            return "未找到城市：" + city + "（当前仅内置国内主要城市，可尝试周边大城市或省会）";
        }

        WeatherResp resp;
        try {
            resp = restClient.get()
                    .uri(WEATHER_API, code)
                    .retrieve()
                    .body(WeatherResp.class);
        } catch (Exception e) {
            log.error("查询天气预报失败: {}", city, e);
            return "天气预报查询失败：" + city + "，原因：" + e.getMessage();
        }

        if (resp == null || resp.data() == null
                || resp.data().forecast() == null || resp.data().forecast().isEmpty()) {
            return "天气预报查询失败：" + city;
        }

        List<Forecast> list = resp.data().forecast();
        String cityName = resp.cityInfo() == null ? city : resp.cityInfo().city();
        int n = Math.min(forecastDays, list.size());

        StringBuilder sb = new StringBuilder("%s 未来 %d 天预报：\n".formatted(cityName, n));
        for (int i = 0; i < n; i++) {
            Forecast f = list.get(i);
            sb.append("\n").append(nvl(f.ymd())).append("（").append(nvl(f.week())).append("）：")
                    .append(nvl(f.type()))
                    .append("，气温 ").append(nvl(f.low())).append(" ~ ").append(nvl(f.high()))
                    .append("，").append(nvl(f.fx())).append(" ").append(nvl(f.fl()))
                    .append("，AQI ").append(nvl(f.aqi()))
                    .append("，日出 ").append(nvl(f.sunrise()))
                    .append("，日落 ").append(nvl(f.sunset()));
        }
        return sb.toString();
    }

    /**
     * 城市名 → 城市编码。支持带"市"后缀，也支持精确匹配。
     */
    private String resolveCityCode(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String key = city.trim();
        String code = CITY_CODES.get(key);
        if (code != null) {
            return code;
        }
        // 去掉"市"/"省"后缀再试一次
        String trimmed = key.replaceAll("(市|省|自治区|特别行政区)$", "");
        return CITY_CODES.get(trimmed);
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String nvl(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    // ==================== 响应模型 ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeatherResp(
            String message,
            int status,
            String date,
            String time,
            @JsonProperty("cityInfo") CityInfo cityInfo,
            @JsonProperty("data") WeatherData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CityInfo(
            String city,
            @JsonProperty("citykey") String citykey,
            String parent,
            String updateTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeatherData(
            String shidu,     // 湿度，如 "43%"
            Object pm25,
            Object pm10,
            String quality,   // 空气质量，如 "优"
            String wendu,     // 当前温度，如 "3.8"
            String ganmao,    // 感冒提示
            List<Forecast> forecast) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Forecast(
            String date,
            String high,      // 如 "高温 10℃"
            String low,       // 如 "低温 3℃"
            String ymd,       // yyyy-MM-dd
            String week,
            String sunrise,
            String sunset,
            Object aqi,
            String fx,        // 风向
            String fl,        // 风力
            String type,      // 天气现象
            String notice) {
    }
}
