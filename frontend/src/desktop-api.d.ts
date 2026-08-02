export {}

declare global {
  interface Window {
    betterSelfDesktop?: {
      isDesktopApp: true
      setCompact(compact: boolean): Promise<void>
      showPet(): Promise<void>
      showSetup(): Promise<void>
      close(): Promise<void>
    }
  }
}
