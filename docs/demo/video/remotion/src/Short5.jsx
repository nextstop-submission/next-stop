import React from 'react';
import { AbsoluteFill, Audio, Img, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame } from 'remotion';
import 'pretendard/dist/web/static/pretendard.css';

// 숏폼 5.1판(1080×1920, 41초). 재료·다시 만들기는 docs/demo/shortform/README.md
// 내레이션 없이 글자·실제 소리·음악으로 간다. 컷은 음악(100BPM) 0.6초 박자 위에만, 전환은 하드 컷만.
// 실사는 Pexels 실제 촬영본(연출 표기, 거치대 폰의 빈 화면은 tools/phone_comp.py로 배송 목록을 합성해 '폰 화면 합성' 표기), 앱은 Pleos 공식 에뮬레이터 녹화(emu.mp4, 6배속)와 같은 에뮬레이터 캡처(c-*.png), 정산만 렌더.
export const FPS = 60;
export const SHORT5_TOTAL = 41;
const fr = (s) => Math.round(s * FPS);
const FONT = 'Pretendard, "Noto Sans KR", sans-serif';
const DARK = 'rgb(12,15,22)', GREEN_BG = 'rgb(44,210,120)', GREEN = '#2ED47A';
const GRADE = 'saturate(0.82) contrast(1.05) brightness(0.95) sepia(0.05)';

// 에뮬레이터 녹화(1280×840, 화면은 y 60~780)와 캡처(1280×720)에서 보여 줄 영역 [x, y, w, h]
const APP = [432, 262, 560, 436];
const APP_C = [432, 202, 560, 436];
const DONE_C = [510, 150, 675, 470];
const LEFT = [0, 250, 420, 330];
const SETTLE = [40, 110, 1540, 860];

const MARK_STAGED = '연출 장면 · 실제 촬영 자료';
const MARK_COMP = '연출 장면 · 실제 촬영 자료 · 폰 화면 합성';
const MARK_REAL = '실제 화면 · Pleos Connect 공식 에뮬레이터';
const MARK_RENDER = '앱 화면 렌더링';

// 원본의 한 영역을 가로 width로 키워 세로 중앙(cy)에 둔다. 배경을 같은 색으로 채워 경계가 보이지 않게 한다.
const Crop = ({ crop, width = 1080, cy = 1000, srcW, srcH, children }) => {
  const [x, y, w, h] = crop, s = width / w;
  return (
    <div style={{ position: 'absolute', left: (1080 - width) / 2, top: cy - (h * s) / 2, width, height: h * s, overflow: 'hidden' }}>
      <div style={{ position: 'absolute', left: -x * s, top: -y * s, width: srcW * s, height: srcH * s }}>{children}</div>
    </div>
  );
};
const Emu = ({ at, rate = 1, crop = APP, bg = DARK, width = 1080 }) => (
  <AbsoluteFill style={{ backgroundColor: bg }}>
    <Crop crop={crop} width={width} srcW={1280} srcH={840}>
      <OffthreadVideo src={staticFile('emu.mp4')} muted trimBefore={fr(at)} playbackRate={rate} style={{ width: '100%', height: '100%' }} />
    </Crop>
  </AbsoluteFill>
);
const Still = ({ src, crop, bg = DARK, srcW = 1280, srcH = 720, width = 1080, cy = 1000 }) => (
  <AbsoluteFill style={{ backgroundColor: bg }}>
    <Crop crop={crop} width={width} cy={cy} srcW={srcW} srcH={srcH}>
      <Img src={staticFile(src)} style={{ width: '100%', height: '100%' }} />
    </Crop>
  </AbsoluteFill>
);
const Stock = ({ src, at, dim = 0 }) => (
  <AbsoluteFill style={{ backgroundColor: '#000' }}>
    <OffthreadVideo src={staticFile(src)} muted trimBefore={fr(at)} style={{ width: 1080, height: 1920, objectFit: 'cover', filter: GRADE }} />
    <AbsoluteFill style={{ background: `linear-gradient(180deg, rgba(0,0,0,.45) 0%, rgba(0,0,0,0) 32%), rgba(0,0,0,${dim})` }} />
  </AbsoluteFill>
);

// 글자: 상자 없는 맨 글자, 한 카드 2줄 이하, 화면 위쪽 같은 자리. 0.08초 페이드로만 나타난다.
// 같은 글자가 컷을 넘어 이어지면(cont) 다시 페이드하지 않는다(깜빡임 방지)
const Cap = ({ text, dark = false, top = 300, size = 68, cont = false }) => {
  const o = cont ? 1 : interpolate(useCurrentFrame(), [0, 5], [0, 1], { extrapolateRight: 'clamp' });
  return (
    <div style={{ position: 'absolute', top, left: 70, right: 70, textAlign: 'center', opacity: o, fontFamily: FONT, fontSize: size, fontWeight: 600, letterSpacing: -1.5, lineHeight: 1.3, whiteSpace: 'pre-line', wordBreak: 'keep-all', color: dark ? '#14181f' : '#fff', textShadow: dark ? 'none' : '0 2px 18px rgba(0,0,0,.35)' }}>{text}</div>
  );
};
const Mark = ({ text, dark = false }) => (
  <div style={{ position: 'absolute', top: 160, left: 60, fontFamily: FONT, fontSize: 26, fontWeight: 500, letterSpacing: -0.3, color: dark ? 'rgba(20,24,31,.55)' : 'rgba(255,255,255,.6)' }}>{text}</div>
);

// [시작, 끝, 화면, 글자, 표기]
const SHOTS = [
  [0.0, 1.2, <Stock src="s6170792c.mp4" at={2.4} />, '정차하면, 폰.', 'stagedComp'],
  [1.2, 2.4, <Stock src="s5834558.mp4" at={5.0} />, '주소 찾고,', 'staged'],
  [2.4, 3.6, <Stock src="s6868381.mp4" at={10.4} />, '배송하고,', 'staged'],
  [3.6, 4.8, <Stock src="s6170612.mp4" at={3.0} />, '완료 체크하고,', 'staged'],
  [4.8, 6.0, <Stock src="s6170792c.mp4" at={8.0} />, '또 폰.', 'stagedComp'],
  [6.0, 6.3, <Stock src="s5834558.mp4" at={9.0} />, null, 'staged'],
  [6.3, 6.6, <Stock src="s6868381.mp4" at={14.6} />, null, 'staged'],
  [6.6, 6.9, <Stock src="s6170612.mp4" at={7.0} />, null, 'staged'],
  [6.9, 7.2, <Stock src="s6170792c.mp4" at={11.0} />, null, 'stagedComp'],
  [7.2, 9.0, <AbsoluteFill style={{ backgroundColor: '#000' }} />, ['하루 수십 번.', 860], null],
  [9.0, 10.8, <AbsoluteFill style={{ backgroundColor: '#000' }} />, ['이제, 차가 합니다.', 860], null],
  [10.8, 12.6, <Logo />, null, null],
  [12.6, 14.4, <Emu at={15.4} rate={0.5} />, '기어 P,', 'real'],
  [14.4, 15.6, <Stock src="s9520614.mp4" at={8.9} />, '문 열고,', 'staged'],
  [15.6, 16.8, <Emu at={21.6} rate={0.75} crop={LEFT} bg="#fff" width={1000} />, ['문 열고,', 300, 'dark'], 'realDark'],
  [16.8, 18.0, <Stock src="s9520614.mp4" at={16.4} />, '닫고,', 'staged'],
  [18.0, 19.2, <Emu at={24.7} rate={0.5} />, '닫고,', 'real'],
  [19.2, 19.8, <Emu at={28.9} rate={0.5} />, '다시 D.', 'real'],
  [19.8, 21.0, <Still src="c-done.png" crop={DONE_C} bg={GREEN_BG} />, ['1건, 자동 완료.', 300, 'dark'], 'realDark'],
  [21.0, 23.4, <Emu at={29.25} />, '다음 집은,\n바로 내비로.', 'real'],
  [23.4, 24.0, <Emu at={39.0} crop={LEFT} bg="#fff" width={1000} />, ['화면은\n한 번도 안 만집니다.', 300, 'dark'], 'realDark'],
  [24.0, 24.6, <Emu at={45.5} />, '화면은\n한 번도 안 만집니다.', 'real'],
  [24.6, 25.2, <Emu at={58.5} crop={LEFT} bg="#fff" width={1000} />, ['화면은\n한 번도 안 만집니다.', 300, 'dark'], 'realDark'],
  [25.2, 25.8, <Emu at={63.2} />, '화면은\n한 번도 안 만집니다.', 'real'],
  [25.8, 26.4, <Emu at={66.0} />, '화면은\n한 번도 안 만집니다.', 'real'],
  [26.4, 28.8, <Stock src="s19178495.mp4" at={2.0} />, '달리는 동안엔', 'staged'],
  [28.8, 31.2, <Still src="c-drive.png" crop={APP_C} />, '다음 집, 남은 건수.\n딱 두 줄.', 'real'],
  [31.2, 34.2, <Still src="07-settlement.png" crop={SETTLE} srcW={2560} srcH={1440} cy={1060} bg="rgb(14,17,22)" />, '끝나면,\n오늘 번 돈까지.', 'render'],
  [34.2, 37.8, <Stock src="s19178495.mp4" at={7.0} dim={0.35} />, null, 'staged'],
  [37.8, SHORT5_TOTAL, <EndCard />, null, null],
];

function Logo() {
  return (
    <AbsoluteFill style={{ backgroundColor: DARK, alignItems: 'center', justifyContent: 'center', fontFamily: FONT }}>
      <div style={{ color: GREEN, fontSize: 200, fontWeight: 700, letterSpacing: -8, lineHeight: 1 }}>다음집</div>
      <div style={{ color: 'rgba(255,255,255,.72)', fontSize: 42, fontWeight: 500, marginTop: 36, letterSpacing: -0.5 }}>Pleos Connect 차량용 앱</div>
    </AbsoluteFill>
  );
}
function EndCard() {
  const o = interpolate(useCurrentFrame(), [0, fr(0.3)], [0, 1], { extrapolateRight: 'clamp' });
  return (
    <AbsoluteFill style={{ backgroundColor: DARK, alignItems: 'center', justifyContent: 'center', fontFamily: FONT, opacity: o }}>
      <div style={{ color: GREEN, fontSize: 180, fontWeight: 700, letterSpacing: -7, lineHeight: 1 }}>다음집</div>
      <div style={{ color: 'rgba(255,255,255,.72)', fontSize: 40, fontWeight: 500, marginTop: 34 }}>자기 차로 배송하는 사람을 위한 Pleos Connect 앱</div>
      <div style={{ color: 'rgba(255,255,255,.5)', fontSize: 32, fontWeight: 500, marginTop: 70 }}>#HMG차량용앱개발공모전</div>
    </AbsoluteFill>
  );
}

// 끝 장면 글자(운전대 위, 화면 가운데)
const Closing = () => (
  <>
    <Sequence from={fr(0.2)}><Cap text="손은 운전대에." top={780} size={76} /></Sequence>
    <Sequence from={fr(1.4)}><Cap text="다음 집은, 차가 챙깁니다." top={890} size={76} /></Sequence>
  </>
);

const MARKS = { staged: [MARK_STAGED, false], stagedComp: [MARK_COMP, false], real: [MARK_REAL, false], realDark: [MARK_REAL, true], render: [MARK_RENDER, false] };

// 효과음 [초, 파일, 음량]
const SFX = [
  [0.12, 'buzz.wav', 0.55], [1.5, 'tap.wav', 0.95], [1.82, 'tap.wav', 0.9], [3.95, 'tap.wav', 0.8], [4.86, 'buzz.wav', 0.6], [6.9, 'buzz.wav', 0.45],
  [13.4, 'gear.wav', 0.9], [14.62, 'door_open.wav', 0.75], [17.86, 'door_close.wav', 1.0], [19.24, 'gear.wav', 0.9], [19.82, 'chime.wav', 0.7],
  [23.42, 'door_close.wav', 0.35], [24.62, 'door_close.wav', 0.35], [25.22, 'chime.wav', 0.35],
];

export const ShortV5 = () => {
  const musicA = (f) => interpolate(f / FPS, [0, 7.17, 7.2], [0.55, 0.55, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const musicB = (f) => interpolate(f / FPS, [0, 0.02], [0, 0.7], { extrapolateRight: 'clamp' });
  // 원곡은 38초부터 잦아든다. 26.4초(주행)에서 같은 박자 자리로 4마디(9.6초) 되감아 끝 2초에만 잦아들게 한다
  const musicC = (f) => interpolate(f / FPS, [0, 12.6, 14.6], [0.7, 0.7, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      {SHOTS.map(([a, b, node, cap, mark], i) => {
        const [text, top, dark] = Array.isArray(cap) ? cap : [cap, 300, null];
        const prev = i ? SHOTS[i - 1][3] : null;
        const cont = !!text && (Array.isArray(prev) ? prev[0] : prev) === text;
        return (
          <Sequence key={i} from={fr(a)} durationInFrames={fr(b) - fr(a)}>
            {node}
            {text ? <Cap text={text} top={top} dark={dark === 'dark'} cont={cont} /> : null}
            {mark ? <Mark text={MARKS[mark][0]} dark={MARKS[mark][1]} /> : null}
            {a === 34.2 ? <Closing /> : null}
          </Sequence>
        );
      })}
      <Sequence durationInFrames={fr(7.2)}><Audio src={staticFile('music5.wav')} trimBefore={fr(0.08)} volume={musicA} /></Sequence>
      <Sequence from={fr(10.8)} durationInFrames={fr(26.4) - fr(10.8)}><Audio src={staticFile('music5.wav')} trimBefore={fr(19.28)} volume={musicB} /></Sequence>
      <Sequence from={fr(26.4)}><Audio src={staticFile('music5.wav')} trimBefore={fr(25.28)} volume={musicC} /></Sequence>
      <Sequence durationInFrames={fr(10.8)}><Audio src={staticFile('s16420406.mp4')} trimBefore={fr(2)} volume={(f) => interpolate(f / FPS, [0, 7.2, 10.2, 10.8], [0.18, 0.3, 0.3, 0], { extrapolateRight: 'clamp' })} /></Sequence>
      <Sequence from={fr(26.4)} durationInFrames={fr(2.4)}><Audio src={staticFile('s16420406.mp4')} trimBefore={fr(8)} volume={0.22} /></Sequence>
      <Sequence from={fr(34.2)} durationInFrames={fr(3.6)}><Audio src={staticFile('s16420406.mp4')} trimBefore={fr(12)} volume={0.18} /></Sequence>
      {SFX.map(([s, f, vol], i) => <Sequence key={`x${i}`} from={fr(s)}><Audio src={staticFile(f)} volume={vol} /></Sequence>)}
    </AbsoluteFill>
  );
};
