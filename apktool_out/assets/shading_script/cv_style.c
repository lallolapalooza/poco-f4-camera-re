precision mediump float;

//参数介绍
uniform sampler2D text_source;
uniform float blockCount; //分块总数
uniform float blockOffset; //偏移量(当前属于第几块偏移量)
varying vec2 vTexCoord;


vec3 colorDark(vec3 color, vec2 uv) {
    float count = blockCount;
    float offset = blockOffset;
    uv.x = (uv.x - 0.5) * 0.75 + 0.5;
    //拍照做分块处理
    if (blockCount > 0.0) {
        uv.y = uv.y / count + offset / count;
    }
    float dist = distance(uv, vec2(0.5,0.5));
    float falloff = 0.27;
    float amount = 0.25;
    return color * smoothstep(0.4, falloff * 0.4, dist * amount);
}

vec4 processColor(vec2 uv) {
    //获取输入像素
    vec3 color = texture2D(text_source, uv, 0.0).rgb;
    //处理暗角
    color = colorDark(color, uv);
    return vec4(color, 1.0);
}

void main() {
    vec4 finalColor = processColor(vTexCoord);
    gl_FragColor = finalColor;
}