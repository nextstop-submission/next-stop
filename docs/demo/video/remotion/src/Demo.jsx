import React from 'react';
import { AbsoluteFill, Audio, Easing, Img, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

// 다음집 시연 영상. 시간은 모두 '내레이션 초'로 적고, 화면은 LEAD만큼 뒤로 민다.
// 장면 경계는 narration.mp3의 쉼(-25dB, 0.35초 이상)에서 잰 값이다 — 음성을 바꾸면 S·SUBS의 숫자를 다시 잰다.
// 8번 문장은 scene8_fix.mp3(7.63초)로 바꿨다: 원래 8번 말소리(64.34~70.81초)를 빼고 그 자리에 넣어, 9번부터 D초 뒤로 민다.
export const FPS = 60;
const LEAD = 0.6;
const S8_FROM = 64.34, S8_END = 70.81, FIX = 7.63;
const D = S8_FROM + FIX - S8_END; // 1.16초
export const TOTAL = 85.2;

export const FONT = '"Noto Sans KR", sans-serif';
export const BG = '#0b0f14', GREEN = '#2ED47A', GRAY = '#9aa4b2', AMBER = '#F5A524', RED = '#ff5a5f';

// 화면 출처 표기(실제 동작과 다른 장면은 반드시 표기)
export const REAL_C = '실제 화면 · Pleos Connect 공식 에뮬레이터 · Virtual Console로 기어·문 조작';
export const REAL_NAVI = '실제 화면 · Pleos Connect 에뮬레이터 · NaviHelper로 목적지 전달';
export const RENDER = '앱 화면 렌더링 (에뮬레이터 아님)';
export const STAGED = '연출 장면';
const GRAPHIC = '설명 그래픽';

// 장면 시작(내레이션 초)
const S = { s1: 0, s2: 7.04, s3: 15.06, s4: 28.83, s4b: 33.91, s5: 41.0, s6: 48.23, s6b: 52.0, s7: 56.07, s8: S8_FROM, s9: 71.29 + D, end: TOTAL - LEAD };

// 자막: [시작, 끝, 문장] — 음성의 '피'·'디'는 P·D, '플레오스'는 Pleos
export const SUBS = [
  [0.0, 2.55, '하루 수십 곳을 도는 택배 부업.'],
  [2.59, 4.7, '운전하다 또 휴대폰을 봅니다.'],
  [4.7, 6.6, '다음 집이 어디였더라?'],
  [7.04, 11.6, '다음집은, 자기 차로 배송하는 사람을 위한 차량용 앱입니다.'],
  [15.06, 17.1, '차를 세우고 기어를 P에.'],
  [17.17, 20.0, '문을 열었다가 닫고, 다시 D.'],
  [20.42, 26.0, '이 순서만으로 한 집이 완료되고,\n다음 배송지로 길 안내가 바로 이어집니다.'],
  [26.47, 28.5, '화면은 만지지 않습니다.'],
  [28.83, 33.5, '출발 전에는 오늘 배송지를 가장 짧은 순서로 묶어 줍니다.'],
  [33.91, 40.6, '위치 정보 대신 차량 신호를 쓰기 때문에,\n지하주차장이나 터널에서도 흔들리지 않습니다.'],
  [41.0, 46.0, '달리는 동안에는 다음 집과 남은 건수,\n딱 두 줄만 크게 보여 줍니다.'],
  [46.3, 48.0, '버튼도 없습니다.'],
  [48.23, 52.0, '집에 아무도 없으면 \'나중에\'로 맨 뒤에 보내고,'],
  [52.0, 55.8, '시동을 껐다 켜도 하던 곳에서 그대로 이어집니다.'],
  [56.07, 63.8, '운행이 끝나면 자동으로 완료된 건수와 시간당 실제 수익,\n한 건당 비용까지 정산해 줍니다.'],
  [S8_FROM, S8_FROM + 7.5, 'Pleos 차량 데이터의 문과 주행거리, 차량 표준 기어 신호,\n그리고 내비게이션 연동으로 만들었습니다.'],
];

export const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' };
export const ease = Easing.inOut(Easing.cubic);

// 장면 하나: [from, to) 동안 보이고 끝에서 fadeOut만큼 겹쳐 사라진다(교차 전환).
// origin = 부모 Sequence가 시작하는 내레이션 초(맨 바깥은 -LEAD)
const Scene = ({ from, to, children, fadeIn = 0.35, fadeOut = 0.35, origin = -LEAD }) => {
  const start = Math.round((from - origin) * FPS), dur = Math.round((to - from + fadeOut) * FPS);
  return (
    <Sequence from={start} durationInFrames={dur}>
      <Fader dur={dur} fi={Math.round(fadeIn * FPS)} fo={Math.round(fadeOut * FPS)}>{children}</Fader>
    </Sequence>
  );
};
const Fader = ({ dur, fi, fo, children }) => {
  const fr = useCurrentFrame();
  const o = Math.min(fi ? interpolate(fr, [0, fi], [0, 1], clamp) : 1, fo ? interpolate(fr, [dur - fo, dur], [1, 0], clamp) : 1);
  return <AbsoluteFill style={{ opacity: o }}>{children}</AbsoluteFill>;
};

// 원본 그림의 view 영역[x,y,w,h]을 box에 맞춰 보여 준다. v0→v1로 천천히 확대·이동(정지 화면이라 끊김 없음).
// pv를 주면 그 진행도(0~1)를 쓴다 — 이어지는 장면끼리 확대가 끊기지 않게(교차 전환 때 글자가 겹쳐 보이지 않게)
export const Shot = ({ src, box, v0, v1 = v0, dur = 6, rings = [], label, radius = 22, pv }) => {
  const fr = useCurrentFrame();
  const p = pv ?? interpolate(fr, [0, dur * FPS], [0, 1], { ...clamp, easing: ease });
  const v = v0.map((a, i) => a + (v1[i] - a) * p);
  const sx = box.w / v[2], sy = box.h / v[3];
  return (
    <div style={{ position: 'absolute', left: box.x, top: box.y, width: box.w, height: box.h, overflow: 'hidden', borderRadius: radius, boxShadow: '0 20px 60px rgba(0,0,0,.55)', border: '2px solid rgba(255,255,255,.08)', backgroundColor: '#000' }}>
      <Img src={staticFile(src)} style={{ position: 'absolute', left: -v[0] * sx, top: -v[1] * sy, transformOrigin: '0 0', transform: `scale(${sx}, ${sy})`, imageRendering: 'auto' }} />
      {rings.map((r, i) => {
        const o = interpolate(fr, [r.at * FPS, r.at * FPS + 12], [0, 1], clamp);
        const pulse = 1 + 0.04 * Math.sin((fr - r.at * FPS) / 8);
        return <div key={i} style={{ position: 'absolute', left: (r.x - v[0]) * sx - 10, top: (r.y - v[1]) * sy - 10, width: r.w * sx + 20, height: r.h * sy + 20, border: `6px solid ${AMBER}`, borderRadius: 999, opacity: o, transform: `scale(${pulse})`, boxShadow: `0 0 30px ${AMBER}` }} />;
      })}
      {label ? <Label text={label} /> : null}
    </div>
  );
};
const Label = ({ text }) => (
  <div style={{ position: 'absolute', right: 14, top: 14, padding: '6px 14px', borderRadius: 10, backgroundColor: 'rgba(0,0,0,.72)', color: '#dfe6ee', fontSize: 22, fontWeight: 700, fontFamily: FONT }}>{text}</div>
);
export const CornerLabel = ({ text }) => (
  <div style={{ position: 'absolute', right: 40, top: 32, padding: '8px 18px', borderRadius: 12, backgroundColor: 'rgba(255,255,255,.1)', color: '#dfe6ee', fontSize: 24, fontWeight: 700, fontFamily: FONT }}>{text}</div>
);

// 화면 위쪽 큰 요점 한 줄
const Chip = ({ text, color = GREEN, top = 46, left = 160 }) => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const s = spring({ frame: fr, fps, config: { damping: 16, stiffness: 160 } });
  return <div style={{ position: 'absolute', top, left, padding: '14px 30px', borderRadius: 18, backgroundColor: color, color: '#06110a', fontSize: 44, fontWeight: 900, fontFamily: FONT, transform: `translateY(${(1 - s) * -30}px)`, opacity: s, letterSpacing: -1 }}>{text}</div>;
};

const Subtitles = () => {
  const fr = useCurrentFrame();
  const t = fr / FPS - LEAD;
  const cur = SUBS.find(([a, b]) => t >= a && t < b);
  if (!cur) return null;
  const [a, b, text] = cur;
  const o = Math.min(interpolate(t, [a, a + 0.15], [0, 1], clamp), interpolate(t, [b - 0.15, b], [1, 0], clamp));
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 44, display: 'flex', justifyContent: 'center', opacity: o }}>
      <div style={{ maxWidth: 1640, padding: '14px 34px', borderRadius: 18, backgroundColor: 'rgba(0,0,0,.78)', color: '#fff', fontSize: 50, fontWeight: 700, fontFamily: FONT, lineHeight: 1.3, textAlign: 'center', whiteSpace: 'pre-line', letterSpacing: -0.5 }}>{text}</div>
    </div>
  );
};

// ── 장면 1: 문제(연출) ──
const Problem = () => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const up = spring({ frame: fr - 8, fps, config: { damping: 14, stiffness: 90 } });
  const q = spring({ frame: fr - Math.round(4.1 * FPS), fps, config: { damping: 10, stiffness: 140 } });
  const shake = fr > 2.6 * FPS && fr < 3.6 * FPS ? Math.sin(fr * 1.3) * 4 : 0;
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 30% 40%, #1b2430 0%, #070a0e 70%)', fontFamily: FONT }}>
      {/* 운전대 */}
      <svg width="900" height="900" style={{ position: 'absolute', left: -120, top: 420 }}>
        <circle cx="450" cy="450" r="360" stroke="#2a3340" strokeWidth="60" fill="none" />
        <rect x="150" y="430" width="600" height="60" rx="30" fill="#2a3340" />
        <circle cx="450" cy="460" r="90" fill="#1f2731" />
      </svg>
      <div style={{ position: 'absolute', left: 150, top: 150, color: '#fff', fontSize: 104, fontWeight: 900, lineHeight: 1.15, letterSpacing: -3 }}>
        운전 중,<br /><span style={{ color: RED }}>또 휴대폰.</span>
      </div>
      <div style={{ position: 'absolute', left: 156, top: 430, color: GRAY, fontSize: 40, fontWeight: 700 }}>정차할 때마다 완료 체크 · 다음 주소 확인</div>
      {/* 휴대폰 */}
      <div style={{ position: 'absolute', left: 1180, top: 140 + (1 - up) * 700, transform: `rotate(${-9 + shake * 0.3}deg) translateX(${shake}px)`, width: 380, height: 760, borderRadius: 54, backgroundColor: '#111', border: '12px solid #2c2c2e', boxShadow: '0 30px 80px rgba(0,0,0,.7)' }}>
        <div style={{ position: 'absolute', inset: 18, borderRadius: 34, backgroundColor: '#f4f6f8', overflow: 'hidden' }}>
          <div style={{ padding: '30px 26px 14px', fontSize: 30, fontWeight: 900, color: '#111' }}>오늘 배송 23건</div>
          {['마루동 5구역', '다솜동 3구역', '가람동 1구역', '나래동 2구역', '라온동 4구역', '하늘동 7구역'].map((n, i) => (
            <div key={n} style={{ margin: '0 22px 12px', padding: '16px 18px', borderRadius: 14, backgroundColor: '#fff', fontSize: 26, color: '#333', boxShadow: '0 2px 6px rgba(0,0,0,.08)' }}>{i + 1}. {n}</div>
          ))}
        </div>
      </div>
      {/* 말풍선 */}
      <div style={{ position: 'absolute', left: 820, top: 560, transform: `scale(${q})`, transformOrigin: 'right center', padding: '22px 34px', borderRadius: 26, backgroundColor: AMBER, color: '#1a1200', fontSize: 46, fontWeight: 900 }}>다음 집이 어디였더라?</div>
      <div style={{ position: 'absolute', left: 156, top: 520, padding: '10px 22px', borderRadius: 12, border: `3px solid ${RED}`, color: RED, fontSize: 34, fontWeight: 900, opacity: interpolate(fr, [1.4 * FPS, 1.8 * FPS], [0, 1], clamp) }}>⚠ 운전 중 휴대폰 확인 — 위험</div>
      <CornerLabel text={STAGED} />
    </AbsoluteFill>
  );
};

// ── 장면 2: 이름 + 한 줄 가치 ──
const Title = () => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const a = spring({ frame: fr, fps, config: { damping: 15, stiffness: 120 } });
  const b = spring({ frame: fr - Math.round((11.95 - S.s2) * FPS), fps, config: { damping: 15, stiffness: 120 } });
  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT }}>
      <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 45%, #15301f 0%, #0b0f14 65%)' }} />
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
        <div style={{ transform: `scale(${0.8 + 0.2 * a})`, opacity: a, color: GREEN, fontSize: 220, fontWeight: 900, letterSpacing: -8, lineHeight: 1 }}>다음집</div>
        <div style={{ opacity: a, color: '#fff', fontSize: 50, fontWeight: 700, marginTop: 34 }}>자기 차로 배송하는 사람을 위한 <span style={{ color: GREEN }}>Pleos Connect</span> 차량용 앱</div>
        <div style={{ opacity: b, transform: `translateY(${(1 - b) * 30}px)`, marginTop: 70, padding: '20px 44px', borderRadius: 22, backgroundColor: 'rgba(46,212,122,.14)', border: `3px solid ${GREEN}`, color: '#fff', fontSize: 68, fontWeight: 900 }}>
          이제 <span style={{ color: GREEN }}>차가</span> 배송을 알아봅니다
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

// ── 장면 3: 핵심 동작 P → 문 → D ──
const STEPS = ['① P  정차', '② 문 열고 · 닫고', '③ D  출발', '✓ 배송 완료 + 다음 집 내비'];
const StepBar = ({ active }) => (
  <div style={{ position: 'absolute', top: 34, left: 0, right: 0, display: 'flex', justifyContent: 'center', gap: 18, fontFamily: FONT }}>
    {STEPS.map((s, i) => {
      const on = i === active, done = i < active, result = i === 3;
      return (
        <div key={s} style={{ padding: '14px 30px', borderRadius: 16, fontSize: 40, fontWeight: 900, transform: `scale(${on ? 1.08 : 1})`,
          backgroundColor: on ? GREEN : 'rgba(255,255,255,.06)', color: on ? '#06110a' : done ? GREEN : result ? '#c7d0da' : GRAY,
          border: `3px solid ${on || done ? GREEN : 'rgba(255,255,255,.12)'}` }}>{s}</div>
      );
    })}
  </div>
);
const APP = [428, 40, 836, 470]; // 실화면(1280×720)에서 앱 영역(16:9)
const APP_IN = [440, 150, 690, 388];
const MAIN = { x: 320, y: 128, w: 1280, h: 720 }; // 아래 자막(2줄)과 겹치지 않게
const VC_GEAR = [1330, 395, 270, 169], VC_DOOR = [1150, 640, 440, 275];
const INSET = { x: 1416, y: 520, w: 480, h: 300 };
const Inset = ({ src, view, text }) => (
  <>
    <Shot src={src} box={INSET} v0={view} radius={18} />
    <div style={{ position: 'absolute', left: INSET.x, top: INSET.y - 46, color: '#dfe6ee', fontSize: 26, fontWeight: 700, fontFamily: FONT }}>{text}</div>
  </>
);
const beats = [ // [시작, 끝, 실화면, Virtual Console 조각, 조작 설명, 단계]
  [15.06, 17.17, 'c-park.png', ['vc-2-gear-P.png', VC_GEAR], '기어 P', 0],
  [17.17, 18.8, 'c-open.png', ['vc-3-door-open.png', VC_DOOR], '운전석 문 열림', 1],
  [18.8, 20.42, 'c-closed.png', ['vc-4-door-close.png', VC_DOOR], '운전석 문 닫힘', 1],
  [20.42, 22.6, 'c-done.png', ['vc-5-gear-D.png', VC_GEAR], '기어 D', 2],
  [22.6, 24.3, 'c-next.png', null, '', 3],
];
const Core = () => {
  const fr = useCurrentFrame();
  const t = fr / FPS + S.s3;
  const cur = beats.find(([a, b]) => t >= a && t < b);
  const active = cur ? cur[5] : 3;
  const pv = interpolate(t, [S.s3, 24.3 + 0.2], [0, 1], { ...clamp, easing: ease });
  return (
    <AbsoluteFill style={{ backgroundColor: BG }}>
      {beats.map(([a, b, src, vc, what], i) => (
        <Scene key={src} origin={S.s3} from={a} to={b} fadeIn={i ? 0.18 : 0} fadeOut={0.18}>
          <Shot src={src} box={MAIN} v0={APP} v1={APP_IN} pv={pv} label={REAL_C} />
          {vc ? <Inset src={vc[0]} view={vc[1]} text={`조작: ${what}`} /> : null}
        </Scene>
      ))}
      <Scene origin={S.s3} from={24.3} to={28.83} fadeIn={0.25} fadeOut={0}>
        <Shot src="navi.png" box={MAIN} v0={[860, 90, 1700, 956]} v1={[875, 100, 960, 540]} dur={3.5} label={REAL_NAVI} />
        <Sequence from={Math.round((26.47 - 24.3) * FPS)}>
          <Chip text="화면 터치 0번" left={MAIN.x + 40} top={MAIN.y + MAIN.h - 130} />
        </Sequence>
      </Scene>
      <StepBar active={active} />
    </AbsoluteFill>
  );
};

// ── 장면 4b: 위치 대신 차량 신호(설명 그래픽) ──
const Card = ({ title, rows, mark, color, delay }) => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const s = spring({ frame: fr - delay, fps, config: { damping: 15, stiffness: 120 } });
  return (
    <div style={{ width: 720, height: 600, borderRadius: 30, padding: 50, backgroundColor: 'rgba(255,255,255,.05)', border: `4px solid ${color}`, opacity: s, transform: `translateY(${(1 - s) * 40}px)`, position: 'relative' }}>
      <div style={{ color: '#fff', fontSize: 52, fontWeight: 900 }}>{title}</div>
      {rows.map((r) => <div key={r} style={{ color: '#dfe6ee', fontSize: 40, fontWeight: 700, marginTop: 34 }}>{r}</div>)}
      <div style={{ position: 'absolute', right: 40, bottom: 20, color, fontSize: 170, fontWeight: 900, lineHeight: 1 }}>{mark}</div>
    </div>
  );
};
const Signals = () => (
  <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT, alignItems: 'center', justifyContent: 'center' }}>
    <div style={{ display: 'flex', gap: 60, marginTop: -80 }}>
      <Card title="위치(GPS)로 판단하면" rows={['지하주차장 — 신호 끊김', '터널 — 위치가 튐', '정차·하차를 알 수 없음']} mark="✕" color={RED} delay={0} />
      <Card title="차량 신호로 판단하면" rows={['기어 P · D', '운전석 문 열림 · 닫힘', '주행거리(계기판)']} mark="✓" color={GREEN} delay={Math.round(1.2 * FPS)} />
    </div>
    <CornerLabel text={GRAPHIC} />
  </AbsoluteFill>
);

// ── 장면 6b: 시동을 껐다 켜도 이어서(설명 그래픽) ──
const Resume = () => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const on = spring({ frame: fr - Math.round(1.2 * FPS), fps, config: { damping: 14, stiffness: 120 } });
  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT, alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 70, marginTop: -90 }}>
        <svg width="260" height="260" viewBox="0 0 100 100">
          <circle cx="50" cy="54" r="32" fill="none" stroke={on > 0.5 ? GREEN : GRAY} strokeWidth="8" strokeDasharray="160 60" transform="rotate(-62 50 54)" />
          <line x1="50" y1="12" x2="50" y2="50" stroke={on > 0.5 ? GREEN : GRAY} strokeWidth="8" strokeLinecap="round" />
        </svg>
        <div>
          <div style={{ color: GRAY, fontSize: 48, fontWeight: 900 }}>시동 OFF → <span style={{ color: GREEN, opacity: on }}>ON</span></div>
          <div style={{ display: 'flex', gap: 16, marginTop: 36 }}>
            {[0, 1, 2, 3, 4].map((i) => <div key={i} style={{ width: 110, height: 22, borderRadius: 11, backgroundColor: i < 2 ? GREEN : 'rgba(255,255,255,.15)' }} />)}
          </div>
          <div style={{ color: '#fff', fontSize: 64, fontWeight: 900, marginTop: 30, opacity: on }}>2/5에서 그대로 이어서</div>
        </div>
      </div>
      <CornerLabel text={GRAPHIC} />
    </AbsoluteFill>
  );
};

// ── 장면 8: 쓰는 기술 ──
const TECH = [
  ['Pleos Vehicle SDK', '운전석 문 열림·닫힘 · 주행거리(Odometer)'],
  ['AAOS Car API', '기어 P · D · 주행 중 화면 제한'],
  ['Pleos NaviHelper', '다음 배송지를 내비 목적지로 전달'],
];
const Tech = () => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT, paddingLeft: 200, justifyContent: 'center' }}>
      <div style={{ color: '#fff', fontSize: 66, fontWeight: 900, marginTop: -120 }}>차량 신호만으로 동작합니다</div>
      {TECH.map(([k, v], i) => {
        const s = spring({ frame: fr - Math.round((0.5 + i * 0.9) * FPS), fps, config: { damping: 16, stiffness: 140 } });
        return (
          <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 36, marginTop: 40, opacity: s, transform: `translateX(${(1 - s) * -60}px)` }}>
            <div style={{ width: 520, padding: '18px 28px', borderRadius: 16, backgroundColor: GREEN, color: '#06110a', fontSize: 44, fontWeight: 900 }}>{k}</div>
            <div style={{ color: '#dfe6ee', fontSize: 44, fontWeight: 700 }}>{v}</div>
          </div>
        );
      })}
      <div style={{ color: GRAY, fontSize: 36, fontWeight: 700, marginTop: 56, opacity: interpolate(fr, [3.4 * FPS, 3.9 * FPS], [0, 1], clamp) }}>위치 권한 없음 · 외부 서버 없음 · 터널·지하주차장에서도 같은 판정</div>
    </AbsoluteFill>
  );
};

// ── 장면 9: 마무리 ──
const Outro = () => {
  const fr = useCurrentFrame(), { fps } = useVideoConfig();
  const at = (sec) => spring({ frame: fr - Math.round((sec - S.s9) * FPS), fps, config: { damping: 15, stiffness: 120 } });
  const a = at(71.29 + D), b = at(72.88 + D), c = at(75.21 + D);
  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT, alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
      <div style={{ color: '#fff', fontSize: 84, fontWeight: 900, opacity: a, transform: `translateY(${(1 - a) * 30}px)` }}>손은 운전대에.</div>
      <div style={{ color: '#fff', fontSize: 84, fontWeight: 900, marginTop: 10, opacity: b, transform: `translateY(${(1 - b) * 30}px)` }}>다음 집은, <span style={{ color: GREEN }}>차가 챙깁니다.</span></div>
      <div style={{ marginTop: 90, opacity: c, transform: `scale(${0.85 + 0.15 * c})` }}>
        <div style={{ color: GREEN, fontSize: 150, fontWeight: 900, letterSpacing: -6, lineHeight: 1 }}>다음집</div>
        <div style={{ color: GRAY, fontSize: 38, fontWeight: 700, marginTop: 20 }}>자기 차 택배 부업을 위한 Pleos Connect 앱</div>
      </div>
    </AbsoluteFill>
  );
};

export const Demo = () => {
  const musicVol = (fr) => {
    const t = fr / FPS;
    const narrEnd = 75.96 + D + LEAD;
    const base = interpolate(t, [0, 0.6], [0, 0.1], clamp); // 내레이션 아래 약 -20dB
    const lift = interpolate(t, [narrEnd, narrEnd + 1.2], [0, 0.16], clamp);
    const out = interpolate(t, [TOTAL - 4.5, TOTAL - 0.2], [1, 0], clamp);
    return (base + lift) * out;
  };
  return (
    <AbsoluteFill style={{ backgroundColor: BG }}>
      <Scene from={-LEAD} to={S.s2} fadeIn={0}><Problem /></Scene>
      <Scene from={S.s2} to={S.s3}><Title /></Scene>
      <Scene from={S.s3} to={S.s4}><Core /></Scene>
      <Scene from={S.s4} to={S.s4b}>
        <AbsoluteFill style={{ backgroundColor: BG }}>
          <Shot src="r-plan.png" box={MAIN} v0={[0, 0, 2560, 1440]} v1={[60, 60, 2300, 1294]} dur={5} label={RENDER} />
          <Chip text="가장 짧은 순서로 자동 정리" left={MAIN.x} top={30} />
        </AbsoluteFill>
      </Scene>
      <Scene from={S.s4b} to={S.s5}><Signals /></Scene>
      <Scene from={S.s5} to={S.s6}>
        <AbsoluteFill style={{ backgroundColor: BG }}>
          <Shot src="c-drive.png" box={MAIN} v0={APP} v1={APP_IN} dur={7} label={REAL_C} />
          <Chip text="주행 중(D) · 두 줄 · 버튼 없음" left={MAIN.x} top={30} />
        </AbsoluteFill>
      </Scene>
      <Scene from={S.s6} to={S.s6b}>
        <AbsoluteFill style={{ backgroundColor: BG }}>
          <Shot src="c-ready.png" box={MAIN} v0={[428, 175, 836, 470]} dur={4} rings={[{ x: 557, y: 590, w: 88, h: 38, at: 0.8 }]} label={REAL_C + ' · 버튼 강조는 편집'} />
          <Chip text="아무도 없으면 '나중에' → 맨 뒤로" left={MAIN.x} top={30} color={AMBER} />
        </AbsoluteFill>
      </Scene>
      <Scene from={S.s6b} to={S.s7}><Resume /></Scene>
      <Scene from={S.s7} to={S.s8}>
        <AbsoluteFill style={{ backgroundColor: BG }}>
          <Shot src="r-settle.png" box={MAIN} v0={[40, 40, 1600, 900]} v1={[70, 90, 1440, 810]} dur={8} label={RENDER} />
          <Chip text="운행 끝 → 자동 정산" left={MAIN.x} top={30} />
        </AbsoluteFill>
      </Scene>
      <Scene from={S.s8} to={S.s9}><Tech /></Scene>
      <Scene from={S.s9} to={S.end} fadeOut={0}><Outro /></Scene>
      <Subtitles />
      <Sequence from={Math.round(LEAD * FPS)} durationInFrames={Math.round(S8_FROM * FPS)}><Audio src={staticFile('narration.mp3')} /></Sequence>
      <Sequence from={Math.round((S8_FROM + LEAD) * FPS)}><Audio src={staticFile('scene8_fix.mp3')} /></Sequence>
      <Sequence from={Math.round((S8_END + D + LEAD) * FPS)}><Audio src={staticFile('narration.mp3')} trimBefore={Math.round(S8_END * FPS)} /></Sequence>
      <Audio src={staticFile('music.mp3')} volume={musicVol} />
    </AbsoluteFill>
  );
};
