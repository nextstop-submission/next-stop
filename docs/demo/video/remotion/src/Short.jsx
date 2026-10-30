import React from 'react';
import { AbsoluteFill, Audio, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { FPS, FONT, BG, RENDER, STAGED, clamp, ease, Shot } from './Demo';

// 숏폼 챌린지용 세로 영상(1080×1920). 숏폼 전용 내레이션(shortform_narration.mp3, 42.45초) 한 줄에 장면을 맞춘다.
// 구성은 공식 예시대로 Spot(불편한 순간) → Solve(앱으로 해결) → Scene(사용 장면).
// 시간은 모두 '내레이션 초'로 적고 화면은 LEAD만큼 뒤로 민다. 경계는 음성의 쉼(-25dB, 0.25초 이상)에서 잰 값이다.
// 바꿔 끼울 자리(연출 장면, AI 생성): props.intro = 첫 0~4초 세로 영상, props.gear = '다시 D' 대목의 기어 영상(public/ 안 파일 이름).
const GREEN = '#2ED47A', GRAY = '#9aa4b2', AMBER = '#F5A524', RED = '#ff5a5f';
const NARR = 'shortform_narration.mp3', NARR_LEN = 42.45;
const LEAD = 0.25;
const INTRO_SEC = 4;
export const SHORT_TOTAL = 46.5;
const fr = (sec) => Math.round(sec * FPS);
const v = (narr) => narr + LEAD; // 내레이션 초 → 영상 초
const S = { spot: 0, title: 8.2, core: 11.4, drive: 25.2, settle: 33.5, outro: 38.4 };

// 자막: [시작, 끝, 문장] — 내레이션 초. 음성의 '피'·'디'는 P·D
const SUBS = [
  [0.0, 1.93, '운전하다 또 휴대폰.'],
  [2.44, 3.91, '다음 집이 어디였더라?'],
  [4.5, 8.03, '하루 수십 집을 도는 택배 부업, 매번 이렇습니다.'],
  [8.41, 9.99, '그래서 만들었습니다.'],
  [10.29, 11.1, '다음집.'],
  [11.92, 13.5, '차를 세우고, P.'],
  [14.23, 16.77, '문을 열고, 닫고, 다시 D.'],
  [17.59, 22.52, '이 순서만으로 한 집이 완료되고, 다음 집 길 안내가 바로 뜹니다.'],
  [23.31, 24.91, '화면은 만지지 않습니다.'],
  [25.46, 29.18, '달리는 동안엔 딱 두 줄. 다음 집과 남은 건수.'],
  [30.06, 33.13, '지하주차장에서도 차량 신호로 정확하게.'],
  [33.9, 38.05, '운행이 끝나면, 오늘 진짜 번 돈까지 자동으로 정산.'],
];

// 구간 하나(영상 초): 앞 0.3초 들어오고 끝에서 0.3초 겹쳐 사라진다
const Seg = ({ from, to, children, fadeIn = 0.3, fadeOut = 0.3 }) => {
  const dur = fr(to - from + fadeOut);
  return (
    <Sequence from={fr(from)} durationInFrames={dur}>
      <Fade dur={dur} fi={fr(fadeIn)} fo={fr(fadeOut)}>{children}</Fade>
    </Sequence>
  );
};
const Fade = ({ dur, fi, fo, children }) => {
  const f = useCurrentFrame();
  const o = Math.min(fi ? interpolate(f, [0, fi], [0, 1], clamp) : 1, fo ? interpolate(f, [dur - fo, dur], [1, 0], clamp) : 1);
  return <AbsoluteFill style={{ opacity: o }}>{children}</AbsoluteFill>;
};
// 장면 전체를 아주 천천히 확대해 정지 화면이 1.5초 넘게 멈춰 있지 않게 한다
const Drift = ({ dur, children, to = 1.05 }) => {
  const s = interpolate(useCurrentFrame(), [0, fr(dur)], [1, to], clamp);
  return <AbsoluteFill style={{ transform: `scale(${s})` }}>{children}</AbsoluteFill>;
};

const Tag = ({ text, color }) => (
  <div style={{ position: 'absolute', left: 54, top: 150, padding: '10px 24px', borderRadius: 14, backgroundColor: color, color: '#06110a', fontSize: 38, fontWeight: 900, fontFamily: FONT, letterSpacing: 1 }}>{text}</div>
);
const Pop = ({ text, top, color = GREEN, delay = 0, size = 54 }) => {
  const f = useCurrentFrame(), { fps } = useVideoConfig();
  const s = spring({ frame: f - delay, fps, config: { damping: 12, stiffness: 180 } });
  return <div style={{ position: 'absolute', top, left: 0, right: 0, display: 'flex', justifyContent: 'center', opacity: Math.min(1, s), transform: `scale(${0.6 + 0.4 * s})` }}>
    <div style={{ padding: '16px 34px', borderRadius: 20, backgroundColor: color, color: '#06110a', fontSize: size, fontWeight: 900, fontFamily: FONT, letterSpacing: -1, boxShadow: '0 12px 40px rgba(0,0,0,.45)' }}>{text}</div>
  </div>;
};
const StagedLabel = ({ text = STAGED }) => (
  <div style={{ position: 'absolute', right: 54, top: 156, padding: '8px 18px', borderRadius: 12, backgroundColor: 'rgba(0,0,0,.55)', color: '#dfe6ee', fontSize: 30, fontWeight: 700, fontFamily: FONT }}>{text}</div>
);

const Subtitles = () => {
  const n = useCurrentFrame() / FPS - LEAD;
  const cur = SUBS.find(([a, b]) => n >= a && n < b);
  if (!cur) return null;
  const [a, b, text] = cur;
  const o = Math.min(interpolate(n, [a, a + 0.1], [0, 1], clamp), interpolate(n, [b - 0.1, b], [1, 0], clamp));
  return (
    <div style={{ position: 'absolute', left: 40, right: 40, bottom: 300, display: 'flex', justifyContent: 'center', opacity: o }}>
      <div style={{ padding: '16px 28px', borderRadius: 20, backgroundColor: 'rgba(0,0,0,.82)', color: '#fff', fontSize: 52, fontWeight: 700, fontFamily: FONT, lineHeight: 1.32, textAlign: 'center', wordBreak: 'keep-all', letterSpacing: -0.5 }}>{text}</div>
    </div>
  );
};

// ── Spot: 첫 프레임부터 문제가 보이고, 1초 안에 움직임이 시작된다(흔들림·알림·말풍선) ──
const TOASTS = [[0.45, '새 배송 1건 추가'], [1.15, '주소 확인 요청'], [1.85, '다음 배송지 변경'], [3.4, '완료 체크 필요']];
const Spot = () => {
  const f = useCurrentFrame(), { fps } = useVideoConfig();
  const t = f / FPS;
  const sp = (sec, cfg = { damping: 12, stiffness: 170 }) => spring({ frame: f - fr(sec), fps, config: cfg });
  const shakeAmp = interpolate(t, [0, 1.6, 2.2, 9], [10, 10, 3, 3], clamp);
  const sx = Math.sin(t * 37) * shakeAmp, sy = Math.cos(t * 29) * shakeAmp * 0.6;
  const h1 = sp(0, { damping: 11, stiffness: 220 }), h2 = sp(0.3, { damping: 9, stiffness: 220 });
  const phone = sp(0.1, { damping: 13, stiffness: 120 });
  const scroll = (t * 140) % 290;
  const bubble = sp(v(2.44) - 0.1, { damping: 8, stiffness: 200 });
  const wiggle = Math.sin(t * 9) * 3 * Math.min(1, bubble);
  const blink = t > 4.5 ? 0.55 + 0.45 * Math.abs(Math.sin(t * 6)) : 1;
  const items = ['마루동 5구역', '다솜동 3구역', '가람동 1구역', '나래동 2구역', '라온동 4구역', '하늘동 7구역', '새봄동 2구역', '푸른동 6구역'];
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 40%, #1b2430 0%, #070a0e 72%)', fontFamily: FONT, overflow: 'hidden' }}>
      <AbsoluteFill style={{ transform: `translate(${sx}px, ${sy}px)` }}>
        <Tag text="SPOT · 불편했던 순간" color={RED} />
        <div style={{ position: 'absolute', left: 54, top: 270, color: '#fff', fontSize: 132, fontWeight: 900, lineHeight: 1.1, letterSpacing: -4 }}>
          <div style={{ transform: `scale(${1.25 - 0.25 * h1})`, transformOrigin: 'left center' }}>운전 중,</div>
          <div style={{ color: RED, transform: `scale(${1.4 - 0.4 * h2})`, transformOrigin: 'left center', opacity: Math.min(1, h2 * 1.4) }}>또 휴대폰.</div>
        </div>
        <div style={{ position: 'absolute', left: 58, top: 590, color: GRAY, fontSize: 42, fontWeight: 700 }}>정차할 때마다 완료 체크 · 다음 주소 확인</div>
        <div style={{ position: 'absolute', left: 58, top: 660, padding: '10px 22px', borderRadius: 12, border: `3px solid ${RED}`, color: RED, fontSize: 38, fontWeight: 900, opacity: blink }}>⚠ 운전 중 휴대폰 확인 — 위험</div>
        <div style={{ position: 'absolute', left: 560, top: 800 + (1 - phone) * 700, transform: `rotate(${-8 + Math.sin(t * 2.2) * 2}deg)`, width: 400, height: 720, borderRadius: 56, backgroundColor: '#111', border: '12px solid #2c2c2e', boxShadow: '0 30px 80px rgba(0,0,0,.7)' }}>
          <div style={{ position: 'absolute', inset: 18, borderRadius: 34, backgroundColor: '#f4f6f8', overflow: 'hidden' }}>
            <div style={{ position: 'relative', zIndex: 1, padding: '26px 24px 12px', fontSize: 30, fontWeight: 900, color: '#111', backgroundColor: '#f4f6f8' }}>오늘 배송 23건</div>
            <div style={{ transform: `translateY(${-scroll}px)` }}>
              {[...items, ...items].map((n, i) => (
                <div key={i} style={{ margin: '0 20px 12px', padding: '15px 16px', borderRadius: 14, backgroundColor: '#fff', fontSize: 26, color: '#333', boxShadow: '0 2px 6px rgba(0,0,0,.08)' }}>{(i % items.length) + 1}. {n}</div>
              ))}
            </div>
          </div>
        </div>
        {TOASTS.map(([at, text], i) => {
          const s = sp(at, { damping: 13, stiffness: 200 });
          return <div key={text} style={{ position: 'absolute', left: 548, top: 850 + i * 86, width: 440, opacity: Math.min(1, s), transform: `translateX(${(1 - s) * 260}px)`, padding: '16px 22px', borderRadius: 18, backgroundColor: 'rgba(255,255,255,.95)', color: '#111', fontSize: 30, fontWeight: 700, boxShadow: '0 10px 30px rgba(0,0,0,.45)', zIndex: 2 }}>🔔 {text}</div>;
        })}
        <div style={{ position: 'absolute', left: 54, top: 930, transform: `scale(${bubble}) rotate(${wiggle}deg)`, transformOrigin: 'left center', padding: '24px 32px', borderRadius: 28, backgroundColor: AMBER, color: '#1a1200', fontSize: 54, fontWeight: 900, lineHeight: 1.2, boxShadow: '0 16px 40px rgba(0,0,0,.5)' }}>다음 집이<br />어디였더라?</div>
      </AbsoluteFill>
      <StagedLabel />
    </AbsoluteFill>
  );
};

// ── Solve: "그래서 만들었습니다. 다음집." ──
const Title = () => {
  const f = useCurrentFrame(), { fps } = useVideoConfig();
  const a = spring({ frame: f, fps, config: { damping: 12, stiffness: 160 } });
  const logo = spring({ frame: f - fr(10.29 - S.title - 0.1), fps, config: { damping: 10, stiffness: 200 } });
  const glow = 0.5 + 0.5 * Math.sin(f / FPS * 2.4);
  return (
    <AbsoluteFill style={{ background: `radial-gradient(ellipse at 50% 38%, rgba(46,212,122,${0.16 + 0.1 * glow}) 0%, #0b0f14 62%)`, fontFamily: FONT }}>
      <Drift dur={S.core - S.title} to={1.06}>
        <AbsoluteFill style={{ alignItems: 'center', textAlign: 'center' }}>
          <div style={{ marginTop: 400, opacity: Math.min(1, a), color: '#fff', fontSize: 64, fontWeight: 900 }}>그래서 만들었습니다.</div>
          <div style={{ marginTop: 40, transform: `scale(${0.6 + 0.4 * logo})`, opacity: Math.min(1, logo), color: GREEN, fontSize: 250, fontWeight: 900, letterSpacing: -8, lineHeight: 1 }}>다음집</div>
          <div style={{ opacity: Math.min(1, logo), color: '#fff', fontSize: 50, fontWeight: 700, marginTop: 40, lineHeight: 1.35 }}>자기 차로 배송하는 사람을 위한<br /><span style={{ color: GREEN }}>Pleos Connect</span> 차량용 앱</div>
        </AbsoluteFill>
      </Drift>
      <Tag text="SOLVE · 다음집이 해결" color={GREEN} />
    </AbsoluteFill>
  );
};

// ── Solve: P → 문 → D (세로 폭의 90%로 크게) ──
const STEPS = ['① P 정차', '② 문 열고·닫고', '③ D 출발', '✓ 완료 + 다음 집'];
const Steps = ({ active }) => (
  <div style={{ position: 'absolute', top: 236, left: 54, right: 54, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, fontFamily: FONT }}>
    {STEPS.map((s, i) => {
      const on = i === active, done = i < active;
      return <div key={s} style={{ padding: '14px 0', textAlign: 'center', borderRadius: 16, fontSize: 42, fontWeight: 900, transform: `scale(${on ? 1.04 : 1})`, backgroundColor: on ? GREEN : 'rgba(255,255,255,.06)', color: on ? '#06110a' : done ? GREEN : GRAY, border: `3px solid ${on || done ? GREEN : 'rgba(255,255,255,.12)'}` }}>{s}</div>;
    })}
  </div>
);
const SQ = { x: 54, y: 410, w: 972, h: 972 };
const APP_SQ = [440, 150, 470, 470], APP_SQ_IN = [450, 180, 410, 410];
const DONE_SQ = [600, 125, 480, 480], DONE_SQ_IN = [645, 165, 390, 390]; // ✓ 1/5는 앱 가운데에 있어 따로 맞추고 더 당긴다
const VC_GEAR = [1330, 395, 270, 169], VC_DOOR = [1150, 640, 440, 275];
const INSET = { x: SQ.x + SQ.w - 350, y: SQ.y + SQ.h - 228, w: 330, h: 206 };
const REAL = '실제 화면 · Pleos 공식 에뮬레이터';
const BEATS = [ // [내레이션 시작, 끝, 실화면, Virtual Console 조각, 단계, 이름]
  [S.core, 14.23, 'c-park.png', ['vc-2-gear-P.png', VC_GEAR], 0, 'park'],
  [14.23, 15.1, 'c-open.png', ['vc-3-door-open.png', VC_DOOR], 1, 'open'],
  [15.1, 15.75, 'c-closed.png', ['vc-4-door-close.png', VC_DOOR], 1, 'closed'],
  [15.75, 16.83, 'c-closed.png', ['vc-5-gear-D.png', VC_GEAR], 2, 'gear'], // 기어 연출 영상(props.gear)이 있으면 이 자리를 덮는다('다시 D')
  [16.83, 17.59, 'c-closed.png', ['vc-5-gear-D.png', VC_GEAR], 2, 'gearReal'],
  [17.59, 20.34, 'c-done.png', null, 3, 'done'],
  [20.34, S.drive + 0.3, 'c-next.png', null, 3, 'next'],
];
const Core = ({ gear, gearFrom }) => {
  const n = useCurrentFrame() / FPS + S.core;
  const cur = BEATS.find(([a, b]) => n >= a && n < b);
  const pv = interpolate(n, [S.core, 17.59], [0, 1], { ...clamp, easing: ease });
  const pvNext = interpolate(n, [20.34, S.drive], [0, 1], { ...clamp, easing: ease });
  return (
    <AbsoluteFill style={{ backgroundColor: BG }}>
      <Tag text="SOLVE · 다음집이 해결" color={GREEN} />
      {BEATS.map(([a, b, src, vc, , id], i) => {
        const done = id === 'done', next = id === 'next', g = id === 'gear' && gear;
        const dur = b - a + 0.18;
        return (
          <Sequence key={id} from={fr(a - S.core)} durationInFrames={fr(dur)}>
            <Fade dur={fr(dur)} fi={i ? fr(0.18) : 0} fo={fr(0.18)}>
              {g ? (
                <div style={{ position: 'absolute', left: SQ.x, top: SQ.y, width: SQ.w, height: SQ.h, overflow: 'hidden', borderRadius: 22 }}>
                  <OffthreadVideo src={staticFile(gear)} muted trimBefore={fr(gearFrom)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                  <div style={{ position: 'absolute', right: 14, top: 14, padding: '6px 14px', borderRadius: 10, backgroundColor: 'rgba(0,0,0,.72)', color: '#dfe6ee', fontSize: 22, fontWeight: 700, fontFamily: FONT }}>연출 장면 (AI 생성)</div>
                </div>
              ) : (
                <Shot src={src} box={SQ} v0={done ? DONE_SQ : APP_SQ} v1={done ? DONE_SQ_IN : next ? [445, 200, 380, 380] : APP_SQ_IN}
                  pv={done ? undefined : next ? pvNext : pv} dur={b - a} label={REAL} />
              )}
              {vc && !g ? <Shot src={vc[0]} box={INSET} v0={vc[1]} radius={16} label="Virtual Console 조작" /> : null}
              {g ? <Pop text="다시 D" top={SQ.y + SQ.h - 200} size={64} delay={4} /> : null}
              {done ? <Pop text="D 한 번 = ✓ 1건 완료" top={SQ.y + SQ.h - 330} color="#ffffff" size={64} delay={6} /> : null}
              {next ? <>
                <Pop text="다음 집 → Pleos 내비로 자동 전달" top={SQ.y + SQ.h - 330} size={46} delay={fr(0.4)} />
                <Pop text="화면 터치 0번" top={SQ.y + SQ.h - 200} color={AMBER} size={50} delay={fr(23.31 - a)} />
              </> : null}
            </Fade>
          </Sequence>
        );
      })}
      <Steps active={cur ? cur[4] : 3} />
    </AbsoluteFill>
  );
};

// ── Scene: 주행 중·정산 ──
const ShotScene = ({ src, view, view2, label, chip, chipColor, h = 972, dur, chip2 }) => (
  <AbsoluteFill style={{ backgroundColor: BG }}>
    <Tag text="SCENE · 실제 사용 장면" color={AMBER} />
    <Pop text={chip} top={250} color={chipColor} delay={6} />
    <Shot src={src} box={{ x: 54, y: 380 + (972 - h) / 2, w: 972, h }} v0={view} v1={view2} dur={dur} label={label} />
    {chip2 ? <Pop text={chip2[0]} top={1180} color={AMBER} size={48} delay={fr(chip2[1])} /> : null}
  </AbsoluteFill>
);

const Outro = () => {
  const f = useCurrentFrame(), { fps } = useVideoConfig();
  const at = (narr) => spring({ frame: f - fr(narr - S.outro), fps, config: { damping: 12, stiffness: 160 } });
  const a = at(38.83), b = at(40.39), c = at(42.3);
  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT }}>
      <Drift dur={SHORT_TOTAL - v(S.outro)} to={1.05}>
        <AbsoluteFill style={{ alignItems: 'center', textAlign: 'center' }}>
          <div style={{ marginTop: 430, color: '#fff', fontSize: 96, fontWeight: 900, opacity: Math.min(1, a), transform: `translateY(${(1 - a) * 40}px)` }}>손은 운전대에.</div>
          <div style={{ color: '#fff', fontSize: 96, fontWeight: 900, marginTop: 16, lineHeight: 1.2, opacity: Math.min(1, b), transform: `translateY(${(1 - b) * 40}px)` }}>다음 집은,<br /><span style={{ color: GREEN }}>차가 챙깁니다.</span></div>
          <div style={{ marginTop: 100, opacity: Math.min(1, c), transform: `scale(${0.8 + 0.2 * c})` }}>
            <div style={{ color: GREEN, fontSize: 190, fontWeight: 900, letterSpacing: -6, lineHeight: 1 }}>다음집</div>
            <div style={{ color: GRAY, fontSize: 40, fontWeight: 700, marginTop: 24 }}>자기 차 택배 부업을 위한 Pleos Connect 앱</div>
            <div style={{ color: '#c7d0da', fontSize: 34, fontWeight: 700, marginTop: 34 }}>#HMG차량용앱개발공모전</div>
          </div>
        </AbsoluteFill>
      </Drift>
    </AbsoluteFill>
  );
};

// 실사 첫 장면 위의 큰 문제 문장(첫 2초 안에 '무슨 순간의 무슨 문제'가 읽히게)
const IntroHook = () => {
  const f = useCurrentFrame(), { fps } = useVideoConfig();
  const a = spring({ frame: f, fps, config: { damping: 11, stiffness: 220 } });
  const b = spring({ frame: f - fr(0.35), fps, config: { damping: 9, stiffness: 220 } });
  return (
    <div style={{ position: 'absolute', left: 54, top: 250, fontFamily: FONT, fontSize: 132, fontWeight: 900, lineHeight: 1.1, letterSpacing: -4, color: '#fff', textShadow: '0 6px 30px rgba(0,0,0,.8)' }}>
      <div style={{ transform: `scale(${1.2 - 0.2 * a})`, transformOrigin: 'left center' }}>운전 중,</div>
      <div style={{ color: RED, transform: `scale(${1.4 - 0.4 * b})`, transformOrigin: 'left center', opacity: Math.min(1, b * 1.4) }}>또 휴대폰.</div>
    </div>
  );
};

export const ShortVideo = ({ intro = null, gear = null, gearFrom = 0 }) => {
  const narrEnd = v(NARR_LEN);
  const music = (f) => {
    const t = f / FPS;
    const base = interpolate(t, [0, 0.3], [0.06, 0.08], clamp); // 내레이션 아래 약 -22dB
    const lift = interpolate(t, [narrEnd, narrEnd + 0.8], [0, 0.18], clamp);
    return (base + lift) * interpolate(t, [SHORT_TOTAL - 2.5, SHORT_TOTAL - 0.1], [1, 0], clamp);
  };
  return (
    <AbsoluteFill style={{ backgroundColor: BG }}>
      <Seg from={0} to={v(S.title)} fadeIn={0}><Spot /></Seg>
      {intro ? (
        <Seg from={0} to={INTRO_SEC} fadeIn={0} fadeOut={0.4}>
          <OffthreadVideo src={staticFile(intro)} muted style={{ width: 1080, height: 1920, objectFit: 'cover' }} />
          <AbsoluteFill style={{ background: 'linear-gradient(180deg, rgba(0,0,0,.72) 0%, rgba(0,0,0,0) 34%, rgba(0,0,0,0) 62%, rgba(0,0,0,.6) 100%)' }} />
          <IntroHook />
          <Tag text="SPOT · 불편했던 순간" color={RED} />
          <StagedLabel text="연출 장면 (AI 생성)" />
        </Seg>
      ) : null}
      <Seg from={v(S.title)} to={v(S.core)}><Title /></Seg>
      <Seg from={v(S.core)} to={v(S.drive)}><Core gear={gear} gearFrom={gearFrom} /></Seg>
      <Seg from={v(S.drive)} to={v(S.settle)}>
        <ShotScene src="c-drive.png" view={APP_SQ} view2={[460, 200, 360, 360]} dur={S.settle - S.drive} label={REAL} chip="달리는 동안엔 딱 두 줄" chip2={['지하주차장에서도 차량 신호로', 30.06 - S.drive]} />
      </Seg>
      <Seg from={v(S.settle)} to={v(S.outro)}>
        <ShotScene src="r-settle.png" view={[90, 40, 1480, 930]} view2={[95, 60, 1430, 899]} h={611} dur={S.outro - S.settle} label={RENDER} chip="오늘 번 돈까지 자동 정산" />
      </Seg>
      <Seg from={v(S.outro)} to={SHORT_TOTAL} fadeOut={0}><Outro /></Seg>
      <Subtitles />
      <Sequence from={fr(LEAD)}><Audio src={staticFile(NARR)} /></Sequence>
      <Audio src={staticFile('music.mp3')} volume={music} />
    </AbsoluteFill>
  );
};
