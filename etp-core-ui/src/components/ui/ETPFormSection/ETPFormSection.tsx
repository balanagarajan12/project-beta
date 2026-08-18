import type { ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPFormSection.module.css';

export interface ETPFormSectionProps {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export function ETPFormSection({
  title,
  description,
  children,
  className,
}: ETPFormSectionProps) {
  return (
    <section className={cn(styles.section, className)}>
      <header className={styles.header}>
        <h2 className={styles.title}>{title}</h2>
        {description && <p className={styles.description}>{description}</p>}
      </header>
      <div className={styles.body}>{children}</div>
    </section>
  );
}
