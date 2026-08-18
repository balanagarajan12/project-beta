import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPTabs.module.css';

interface TabsContextValue {
  activeId: string;
  setActiveId: (id: string) => void;
}

const TabsContext = createContext<TabsContextValue | null>(null);

export interface ETPTabsProps {
  defaultTabId: string;
  children: ReactNode;
  className?: string;
}

export function ETPTabs({ defaultTabId, children, className }: ETPTabsProps) {
  const [activeId, setActiveId] = useState(defaultTabId);

  return (
    <TabsContext.Provider value={{ activeId, setActiveId }}>
      <div className={cn(styles.tabs, className)}>{children}</div>
    </TabsContext.Provider>
  );
}

export function ETPTabList({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <ul role="tablist" className={cn(styles.list, className)}>
      {children}
    </ul>
  );
}

export interface ETPTabProps {
  id: string;
  children: ReactNode;
}

export function ETPTab({ id, children }: ETPTabProps) {
  const ctx = useContext(TabsContext);
  if (!ctx) throw new Error('ETPTab must be used within ETPTabs');

  return (
    <li role="presentation">
      <button
        type="button"
        role="tab"
        id={`tab-${id}`}
        className={styles.tab}
        aria-selected={ctx.activeId === id}
        aria-controls={`panel-${id}`}
        onClick={() => ctx.setActiveId(id)}
      >
        {children}
      </button>
    </li>
  );
}

export interface ETPTabPanelProps {
  id: string;
  children: ReactNode;
}

export function ETPTabPanel({ id, children }: ETPTabPanelProps) {
  const ctx = useContext(TabsContext);
  if (!ctx) throw new Error('ETPTabPanel must be used within ETPTabs');

  const isActive = ctx.activeId === id;

  return (
    <div
      role="tabpanel"
      id={`panel-${id}`}
      className={isActive ? styles.panel : styles.panelHidden}
      aria-labelledby={`tab-${id}`}
      hidden={!isActive}
    >
      {children}
    </div>
  );
}
