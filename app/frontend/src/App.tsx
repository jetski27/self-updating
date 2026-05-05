import { Link, Route, Routes } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import Settings from './pages/Settings';

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <img src="/azry.png" alt="Azry" className="brand-logo" />
          <div className="brand-text">
            <span className="brand-name">PoS Agent</span>
            <span className="brand-vendor">by Azry</span>
          </div>
        </div>
        <nav>
          <Link to="/">Dashboard</Link>
          <Link to="/settings">Settings</Link>
        </nav>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </main>
    </div>
  );
}
