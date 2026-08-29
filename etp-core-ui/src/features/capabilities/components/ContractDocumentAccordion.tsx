import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import type { CapabilityContractDocument } from '../types';
import {
  CONTRACT_DOCUMENT_SECTIONS,
  formatContractSectionValue,
  isEmptyRecord,
} from '../contractDocumentPresentation';
import styles from './ContractDocumentAccordion.module.css';

export interface ContractDocumentAccordionProps {
  document: CapabilityContractDocument | null;
}

export function ContractDocumentAccordion({ document }: ContractDocumentAccordionProps) {
  const [expandedKey, setExpandedKey] = useState<string | null>(null);

  if (!document) {
    return <p className={styles.empty}>No contract document specified.</p>;
  }

  return (
    <div className={styles.root}>
      {CONTRACT_DOCUMENT_SECTIONS.map(({ key, label }) => {
        const sectionData = document[key];
        const empty = isEmptyRecord(sectionData as Record<string, unknown> | undefined);
        const isExpanded = expandedKey === key;

        return (
          <div key={key} className={styles.section}>
            <button
              type="button"
              className={styles.sectionTrigger}
              aria-expanded={isExpanded}
              onClick={() => setExpandedKey(isExpanded ? null : key)}
            >
              <span className={styles.sectionLabel}>{label}</span>
              {isExpanded ? (
                <ChevronDown size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
              ) : (
                <ChevronRight size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
              )}
            </button>
            {isExpanded && (
              <div className={styles.sectionBody}>
                {empty ? (
                  <p className={styles.empty}>No data specified</p>
                ) : (
                  <ul className={styles.list}>
                    {Object.entries(sectionData as Record<string, unknown>).map(([fieldKey, value]) => {
                      const lines = formatContractSectionValue(value);
                      if (lines.length === 0) {
                        return (
                          <li key={fieldKey}>
                            <span className={styles.fieldLabel}>{fieldKey}</span>
                            <span className={styles.emptyInline}>No data specified</span>
                          </li>
                        );
                      }
                      return (
                        <li key={fieldKey}>
                          <span className={styles.fieldLabel}>{fieldKey}</span>
                          <ul className={styles.nestedList}>
                            {lines.map((line, index) => (
                              <li key={`${fieldKey}-${index}`}>{line}</li>
                            ))}
                          </ul>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
