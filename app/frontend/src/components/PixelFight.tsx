import { useEffect, useRef } from 'react';

/**
 * Two chunky pixel-art characters in an endless fight loop. Drawn entirely
 * with filled rectangles on a Canvas — no sprite sheets, no images. Each
 * character has a state machine (idle → windup → punch → recover) and the
 * loop alternates which fighter is attacking. ~60 fps, integer pixel grid
 * for that classic GBA look.
 */

type Action = 'idle' | 'windup' | 'punch' | 'hit' | 'recoil';

interface Fighter {
  x: number;          // grid x (logical pixel grid, not screen px)
  baseX: number;
  facing: 1 | -1;
  body: string;
  skin: string;
  trim: string;
  action: Action;
}

const PIXEL = 5;            // each "pixel" = 5×5 screen px
const CYCLE = 160;          // frames per full attack-and-counterattack cycle

export default function PixelFight() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    const sizeCanvas = () => {
      const cssW = canvas.clientWidth;
      const cssH = canvas.clientHeight;
      canvas.width = Math.round(cssW * dpr);
      canvas.height = Math.round(cssH * dpr);
      ctx.imageSmoothingEnabled = false;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    sizeCanvas();
    window.addEventListener('resize', sizeCanvas);

    let frame = 0;
    let rafId = 0;

    const fighters: [Fighter, Fighter] = [
      { x: 0, baseX: 0, facing: 1,  body: '#e74c3c', skin: '#f4c089', trim: '#a93226', action: 'idle' },
      { x: 0, baseX: 0, facing: -1, body: '#3498db', skin: '#f4c089', trim: '#21618c', action: 'idle' },
    ];

    const tick = () => {
      const w = canvas.clientWidth;
      const h = canvas.clientHeight;

      // Position fighters relative to canvas size (recompute every frame so
      // resize is automatic).
      const groundY = h - 16;
      const cx = w / 2;
      const sep = 56;
      fighters[0].baseX = cx - sep;
      fighters[1].baseX = cx + sep;

      // State machine for the cycle.
      const f1 = fighters[0];
      const f2 = fighters[1];
      f1.action = 'idle';
      f2.action = 'idle';
      f1.x = f1.baseX;
      f2.x = f2.baseX;

      // f1 attacks during 30..60
      if (frame >= 30 && frame < 40) f1.action = 'windup';
      else if (frame >= 40 && frame < 48) {
        f1.action = 'punch';
        f2.action = 'hit';
        f2.x = f2.baseX + 5;
      } else if (frame >= 48 && frame < 60) {
        f2.action = 'recoil';
        f2.x = f2.baseX + Math.max(0, 5 - (frame - 48) * 0.5);
      }

      // f2 attacks during 110..140
      if (frame >= 110 && frame < 120) f2.action = 'windup';
      else if (frame >= 120 && frame < 128) {
        f2.action = 'punch';
        f1.action = 'hit';
        f1.x = f1.baseX - 5;
      } else if (frame >= 128 && frame < 140) {
        f1.action = 'recoil';
        f1.x = f1.baseX - Math.max(0, 5 - (frame - 128) * 0.5);
      }

      // Background gradient
      const grad = ctx.createLinearGradient(0, 0, 0, h);
      grad.addColorStop(0, '#1a1042');
      grad.addColorStop(0.6, '#2a1858');
      grad.addColorStop(1, '#0f0826');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, w, h);

      // Pixel stars in background
      ctx.fillStyle = 'rgba(255,255,255,0.55)';
      for (let i = 0; i < 30; i++) {
        const sx = (i * 73 + (frame * 0.2)) % w;
        const sy = ((i * 47) % (h - 30));
        ctx.fillRect(Math.floor(sx), Math.floor(sy), 2, 2);
      }

      // Ground
      ctx.fillStyle = '#0a0420';
      ctx.fillRect(0, groundY + PIXEL, w, h - groundY - PIXEL);
      ctx.fillStyle = '#3a2868';
      for (let x = 0; x < w; x += PIXEL * 4) {
        ctx.fillRect(x, groundY, PIXEL * 2, PIXEL);
      }

      // Sway: a tiny breathing motion when idle
      const sway = Math.sin(frame * 0.18) * 0.5;

      drawFighter(ctx, f1, groundY, sway);
      drawFighter(ctx, f2, groundY, sway);

      // Hit spark when punch lands
      if ((frame >= 40 && frame < 46) || (frame >= 120 && frame < 126)) {
        const attacker = frame < 60 ? f1 : f2;
        const sparkX = attacker.x + attacker.facing * (10 * PIXEL);
        const sparkY = groundY - 7 * PIXEL;
        drawSpark(ctx, sparkX, sparkY, frame);
      }

      // VS bobbing
      ctx.fillStyle = '#f5d76e';
      ctx.font = `bold ${Math.round(PIXEL * 4)}px monospace`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const vsY = 22 + Math.sin(frame * 0.15) * 2;
      ctx.fillText('VS', w / 2, vsY);

      frame = (frame + 1) % CYCLE;
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(rafId);
      window.removeEventListener('resize', sizeCanvas);
    };
  }, []);

  return <canvas ref={canvasRef} className="pixel-fight" />;
}

/* ---------- pixel art primitives ---------- */

function px(ctx: CanvasRenderingContext2D, x: number, y: number, color: string) {
  ctx.fillStyle = color;
  ctx.fillRect(Math.round(x), Math.round(y), PIXEL, PIXEL);
}

function rect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, color: string) {
  ctx.fillStyle = color;
  ctx.fillRect(Math.round(x), Math.round(y), w * PIXEL, h * PIXEL);
}

function drawFighter(ctx: CanvasRenderingContext2D, f: Fighter, groundY: number, sway: number) {
  const F = f.facing;
  const X = (g: number) => f.x + g * F * PIXEL;
  const Y = (g: number) => groundY + g * PIXEL;

  // Hit recoils head; idle adds a tiny breathing sway.
  const headDX = f.action === 'hit' ? -2 * F : 0;
  const headDY = f.action === 'hit' ? -1 : Math.round(sway);
  const armBack = f.action === 'windup';
  const armFwd  = f.action === 'punch';

  // Shadow
  ctx.fillStyle = 'rgba(0,0,0,0.4)';
  ctx.beginPath();
  ctx.ellipse(f.x, groundY + PIXEL * 0.5, PIXEL * 4, PIXEL * 1.2, 0, 0, Math.PI * 2);
  ctx.fill();

  // Legs (3 tall)
  rect(ctx, X(-2), Y(-3), 1, 3, f.trim);
  rect(ctx, X( 1), Y(-3), 1, 3, f.trim);
  // Feet
  px(ctx, X(-2), Y(0), '#1a1a1a');
  px(ctx, X( 1), Y(0), '#1a1a1a');

  // Body (3 wide × 4 tall)
  rect(ctx, X(-2), Y(-7), 3, 4, f.body);
  // Belt
  rect(ctx, X(-2), Y(-4), 3, 1, f.trim);

  // Head (3×3)
  rect(ctx, X(-1) + headDX * PIXEL, Y(-10) + headDY * PIXEL, 3, 3, f.skin);
  // Eyes
  px(ctx, X(-1) + headDX * PIXEL, Y(-9) + headDY * PIXEL, '#1a1a1a');
  px(ctx, X( 1) + headDX * PIXEL, Y(-9) + headDY * PIXEL, '#1a1a1a');
  // Hair stripe on top
  rect(ctx, X(-1) + headDX * PIXEL, Y(-10) + headDY * PIXEL, 3, 1, f.trim);

  // Mouth (open during hit)
  if (f.action === 'hit') {
    px(ctx, X(0) + headDX * PIXEL, Y(-8) + headDY * PIXEL, '#1a1a1a');
  }

  // Arms
  if (armFwd) {
    // Front arm fully extended forward, fist at the tip
    rect(ctx, X(2),  Y(-6), 2, 1, f.skin);
    rect(ctx, X(4),  Y(-6), 1, 1, f.trim);     // fist
    // Back arm idle
    px(ctx, X(-3), Y(-5), f.skin);
  } else if (armBack) {
    // Punching arm pulled back
    rect(ctx, X(-3), Y(-7), 2, 1, f.skin);
    px(ctx, X(2), Y(-5), f.skin);
  } else {
    // Idle / hit / recoil — both arms at sides
    px(ctx, X(-2), Y(-6), f.skin);
    px(ctx, X( 2), Y(-6), f.skin);
    px(ctx, X(-2), Y(-5), f.skin);
    px(ctx, X( 2), Y(-5), f.skin);
  }
}

function drawSpark(ctx: CanvasRenderingContext2D, cx: number, cy: number, frame: number) {
  const colors = ['#fff7c2', '#ffe066', '#f59e0b'];
  const ring = ((frame % 6) / 6) * 3;
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * Math.PI * 2;
    const r = (3 + ring) * PIXEL;
    const x = cx + Math.cos(a) * r;
    const y = cy + Math.sin(a) * r * 0.6;
    px(ctx, x, y, colors[i % colors.length]);
  }
  // Center burst
  ctx.fillStyle = '#fffbeb';
  ctx.fillRect(cx - PIXEL, cy - PIXEL, PIXEL * 2, PIXEL * 2);
}
