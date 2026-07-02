/// <reference types="vite/client" />

declare module 'wawoff2' {
  export function decompress(buffer: Uint8Array): Promise<Uint8Array>;
}

declare module 'virtual:noto-sans-sc-ttf' {
  const url: string;
  export default url;
}

declare module 'virtual:noto-serif-sc-ttf' {
  const url: string;
  export default url;
}

declare module 'docx-preview' {
  export interface RenderOptions {
    className?: string;
    inWrapper?: boolean;
    ignoreWidth?: boolean;
    ignoreHeight?: boolean;
    ignoreFonts?: boolean;
    breakPages?: boolean;
  }

  export function renderAsync(
    data: Blob | ArrayBuffer | Uint8Array,
    bodyContainer: HTMLElement,
    styleContainer?: HTMLElement,
    options?: RenderOptions
  ): Promise<void>;
}
