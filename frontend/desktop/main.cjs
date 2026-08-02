const { app, BrowserWindow, ipcMain, screen } = require('electron')
const path = require('node:path')

const appUrl = process.env.BETTER_SELF_APP_URL || 'http://127.0.0.1:5173'
const petSize = { width: 300, height: 290 }
const ballSize = { width: 76, height: 76 }
let setupWindow = null
let petWindow = null

const hasSingleInstanceLock = app.requestSingleInstanceLock()
if (!hasSingleInstanceLock) app.quit()

if (process.defaultApp && process.argv.length >= 2) {
  app.setAsDefaultProtocolClient('better-self', process.execPath, [path.resolve(process.argv[1])])
} else {
  app.setAsDefaultProtocolClient('better-self')
}

function pageUrl(route) {
  return new URL(route, appUrl).toString()
}

function bottomRightBounds(size) {
  const { workArea } = screen.getPrimaryDisplay()
  return {
    ...size,
    x: workArea.x + workArea.width - size.width - 24,
    y: workArea.y + workArea.height - size.height - 24,
  }
}

function sharedPreferences() {
  return {
    preload: path.join(__dirname, 'preload.cjs'),
    contextIsolation: true,
    nodeIntegration: false,
    sandbox: true,
  }
}

function showSetupWindow(route = '/desktop-pet') {
  if (setupWindow && !setupWindow.isDestroyed()) {
    setupWindow.loadURL(pageUrl(route))
    setupWindow.show()
    setupWindow.focus()
    return setupWindow
  }

  setupWindow = new BrowserWindow({
    width: 1120,
    height: 780,
    minWidth: 900,
    minHeight: 660,
    title: '更好的自己 - 桌宠设置',
    backgroundColor: '#f8f4ee',
    webPreferences: sharedPreferences(),
  })
  setupWindow.setMenuBarVisibility(false)
  setupWindow.loadURL(pageUrl(route))
  setupWindow.on('closed', () => { setupWindow = null })
  return setupWindow
}

function applyPetWindowBehavior(window) {
  window.setAlwaysOnTop(true, process.platform === 'darwin' ? 'floating' : 'normal')
  window.setSkipTaskbar(true)
  window.setFullScreenable(false)
  if (process.platform === 'darwin') {
    window.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  }
}

function showPetWindow() {
  if (petWindow && !petWindow.isDestroyed()) {
    petWindow.show()
    applyPetWindowBehavior(petWindow)
    setupWindow?.close()
    return petWindow
  }

  petWindow = new BrowserWindow({
    ...bottomRightBounds(petSize),
    transparent: true,
    frame: false,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    hasShadow: false,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: sharedPreferences(),
  })
  applyPetWindowBehavior(petWindow)
  petWindow.loadURL(pageUrl('/desktop-pet'))
  petWindow.once('ready-to-show', () => {
    petWindow?.showInactive()
    setupWindow?.close()
  })
  petWindow.on('closed', () => { petWindow = null })
  return petWindow
}

function resizePetWindow(compact) {
  if (!petWindow || petWindow.isDestroyed()) return
  const current = petWindow.getBounds()
  const next = compact ? ballSize : petSize
  petWindow.setBounds({
    width: next.width,
    height: next.height,
    x: current.x + current.width - next.width,
    y: current.y + current.height - next.height,
  }, true)
}

ipcMain.handle('desktop-pet:set-compact', (_event, compact) => resizePetWindow(Boolean(compact)))
ipcMain.handle('desktop-pet:show-pet', () => showPetWindow())
ipcMain.handle('desktop-pet:show-setup', () => {
  showSetupWindow('/partners')
  petWindow?.close()
})
ipcMain.handle('desktop-pet:close', () => petWindow?.close())

app.whenReady().then(() => {
  showSetupWindow()
  app.on('activate', () => {
    if (!setupWindow && !petWindow) showSetupWindow()
  })
})

app.on('second-instance', () => {
  if (petWindow && !petWindow.isDestroyed()) {
    petWindow.show()
    return
  }
  showSetupWindow()
})

app.on('open-url', event => {
  event.preventDefault()
  if (app.isReady()) showSetupWindow()
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
