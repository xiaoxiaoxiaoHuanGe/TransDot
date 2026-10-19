import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
import { applyTheme } from './appearance'
applyTheme()

createRoot(document.getElementById('root')!).render(<App />)
