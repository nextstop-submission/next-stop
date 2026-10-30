import React from 'react';
import { registerRoot, Composition } from 'remotion';
import '@fontsource/noto-sans-kr/400.css';
import '@fontsource/noto-sans-kr/700.css';
import '@fontsource/noto-sans-kr/900.css';
import { Demo, FPS, TOTAL } from './Demo';
import { ShortVideo, SHORT_TOTAL } from './Short';

// 다음집 시연 영상(1920×1080)과 숏폼 챌린지용 세로 영상(1080×1920), 둘 다 60fps
const Root = () => (
  <>
    <Composition id="NextStopDemo" component={Demo} durationInFrames={Math.round(TOTAL * FPS)} fps={FPS} width={1920} height={1080} />
    <Composition id="NextStopShort" component={ShortVideo} durationInFrames={Math.round(SHORT_TOTAL * FPS)} fps={FPS} width={1080} height={1920} />
  </>
);
registerRoot(Root);
