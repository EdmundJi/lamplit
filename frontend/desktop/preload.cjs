const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('betterSelfDesktop', {
  isDesktopApp: true,
  setCompact: compact => ipcRenderer.invoke('desktop-pet:set-compact', compact),
  showPet: () => ipcRenderer.invoke('desktop-pet:show-pet'),
  showSetup: () => ipcRenderer.invoke('desktop-pet:show-setup'),
  close: () => ipcRenderer.invoke('desktop-pet:close'),
})
