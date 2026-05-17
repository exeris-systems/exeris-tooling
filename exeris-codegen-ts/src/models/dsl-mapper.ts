/**
 * DSL Mapper - Maps Java types to TypeScript/Angular types.
 *
 * Handles conversion of Java domain types (BigDecimal, Instant, UUID, etc.)
 * to their TypeScript equivalents and generates appropriate Angular form controls.
 *
 * @author Exeris Team
 * @since 0.2.0
 */

import type { FieldMetadata } from './domain-model.js';

// ============================================================================
// Type Mappings
// ============================================================================

export interface TypeMapping {
  /** TypeScript type */
  tsType: string;
  /** Angular form control type */
  formControl: 'input' | 'textarea' | 'select' | 'checkbox' | 'datepicker' | 'number' | 'file';
  /** HTML input type */
  inputType: string;
  /** Import statement if needed */
  imports?: string[];
  /** Zod schema type */
  zodType: string;
  /** Default value for initialization */
  defaultValue: string;
}

const JAVA_TO_TS_MAP: Record<string, TypeMapping> = {
  // Primitives
  'String': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "''",
  },
  'java.lang.String': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "''",
  },
  'int': {
    tsType: 'number',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().int()',
    defaultValue: '0',
  },
  'Integer': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().int().nullable()',
    defaultValue: 'null',
  },
  'java.lang.Integer': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().int().nullable()',
    defaultValue: 'null',
  },
  'long': {
    tsType: 'number',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number()',
    defaultValue: '0',
  },
  'Long': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().nullable()',
    defaultValue: 'null',
  },
  'java.lang.Long': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().nullable()',
    defaultValue: 'null',
  },
  'double': {
    tsType: 'number',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number()',
    defaultValue: '0',
  },
  'Double': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().nullable()',
    defaultValue: 'null',
  },
  'float': {
    tsType: 'number',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number()',
    defaultValue: '0',
  },
  'Float': {
    tsType: 'number | null',
    formControl: 'number',
    inputType: 'number',
    zodType: 'z.number().nullable()',
    defaultValue: 'null',
  },
  'boolean': {
    tsType: 'boolean',
    formControl: 'checkbox',
    inputType: 'checkbox',
    zodType: 'z.boolean()',
    defaultValue: 'false',
  },
  'Boolean': {
    tsType: 'boolean | null',
    formControl: 'checkbox',
    inputType: 'checkbox',
    zodType: 'z.boolean().nullable()',
    defaultValue: 'null',
  },
  'java.lang.Boolean': {
    tsType: 'boolean | null',
    formControl: 'checkbox',
    inputType: 'checkbox',
    zodType: 'z.boolean().nullable()',
    defaultValue: 'null',
  },

  // Big Numbers
  'BigDecimal': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().regex(/^-?\\d+(\\.\\d+)?$/)',
    defaultValue: "'0'",
  },
  'java.math.BigDecimal': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().regex(/^-?\\d+(\\.\\d+)?$/)',
    defaultValue: "'0'",
  },
  'BigInteger': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().regex(/^-?\\d+$/)',
    defaultValue: "'0'",
  },
  'java.math.BigInteger': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().regex(/^-?\\d+$/)',
    defaultValue: "'0'",
  },

  // Date/Time (Java 8 Time API)
  'Instant': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },
  'java.time.Instant': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },
  'LocalDate': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'date',
    zodType: 'z.string().date()',
    defaultValue: "''",
  },
  'java.time.LocalDate': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'date',
    zodType: 'z.string().date()',
    defaultValue: "''",
  },
  'LocalDateTime': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime({ local: true })',
    defaultValue: "''",
  },
  'java.time.LocalDateTime': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime({ local: true })',
    defaultValue: "''",
  },
  'LocalTime': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'time',
    zodType: 'z.string().time()',
    defaultValue: "''",
  },
  'java.time.LocalTime': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'time',
    zodType: 'z.string().time()',
    defaultValue: "''",
  },
  'ZonedDateTime': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },
  'java.time.ZonedDateTime': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },
  'Duration': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "'PT0S'",
  },
  'java.time.Duration': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "'PT0S'",
  },
  'Period': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "'P0D'",
  },
  'java.time.Period': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string()',
    defaultValue: "'P0D'",
  },

  // Legacy Date (for compatibility)
  'Date': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },
  'java.util.Date': {
    tsType: 'string',
    formControl: 'datepicker',
    inputType: 'datetime-local',
    zodType: 'z.string().datetime()',
    defaultValue: "''",
  },

  // UUID
  'UUID': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().uuid()',
    defaultValue: "''",
  },
  'java.util.UUID': {
    tsType: 'string',
    formControl: 'input',
    inputType: 'text',
    zodType: 'z.string().uuid()',
    defaultValue: "''",
  },

  // Binary data
  'byte[]': {
    tsType: 'string',
    formControl: 'file',
    inputType: 'file',
    zodType: 'z.string()',
    defaultValue: "''",
  },
  'Blob': {
    tsType: 'Blob',
    formControl: 'file',
    inputType: 'file',
    zodType: 'z.instanceof(Blob)',
    defaultValue: 'null as unknown as Blob',
  },

  // Collections (basic)
  'List': {
    tsType: 'unknown[]',
    formControl: 'textarea',
    inputType: 'text',
    zodType: 'z.array(z.unknown())',
    defaultValue: '[]',
  },
  'Set': {
    tsType: 'unknown[]',
    formControl: 'textarea',
    inputType: 'text',
    zodType: 'z.array(z.unknown())',
    defaultValue: '[]',
  },
  'Map': {
    tsType: 'Record<string, unknown>',
    formControl: 'textarea',
    inputType: 'text',
    zodType: 'z.record(z.unknown())',
    defaultValue: '{}',
  },

  // JSON
  'JsonNode': {
    tsType: 'unknown',
    formControl: 'textarea',
    inputType: 'text',
    zodType: 'z.unknown()',
    defaultValue: 'null',
  },
  'com.fasterxml.jackson.databind.JsonNode': {
    tsType: 'unknown',
    formControl: 'textarea',
    inputType: 'text',
    zodType: 'z.unknown()',
    defaultValue: 'null',
  },
};

// ============================================================================
// DSL Mapper Class
// ============================================================================

export class DslMapper {
  /**
   * Wrap a TypeScript type in parentheses before appending `[]` if it
   * contains a union (`|`). Without this, an inner type like
   * `number | null` would interpolate into `number | null[]`, which
   * TypeScript parses as `number | (null[])` — semantically a union
   * of a number OR an array-of-nulls. The intent here is always
   * "an array of <inner>", i.e. `(number | null)[]`.
   */
  private static arrayOf(tsType: string): string {
    return tsType.includes(' | ') ? `(${tsType})[]` : `${tsType}[]`;
  }

  /**
   * Map a Java type to TypeScript type info.
   */
  static mapType(javaType: string): TypeMapping {
    // Check direct mapping
    const direct = JAVA_TO_TS_MAP[javaType];
    if (direct) {
      return direct;
    }

    // Handle simple class name (without package)
    const simpleName = javaType.split('.').pop() ?? javaType;
    const simpleMapping = JAVA_TO_TS_MAP[simpleName];
    if (simpleMapping) {
      return simpleMapping;
    }

    // Handle generics like List<String>
    const genericMatch = javaType.match(/^(\w+)<(.+)>$/);
    if (genericMatch) {
      const [, container, inner] = genericMatch;
      const innerMapping = this.mapType(inner ?? 'unknown');

      if (container === 'List' || container === 'Set' || container === 'Collection') {
        return {
          tsType: DslMapper.arrayOf(innerMapping.tsType),
          formControl: 'textarea',
          inputType: 'text',
          zodType: `z.array(${innerMapping.zodType})`,
          defaultValue: '[]',
        };
      }

      if (container === 'Map') {
        // For Map<K,V> we simplify to Record<string, V>
        const parts = (inner ?? '').split(',').map((s) => s.trim());
        const valueType = parts[1] ? this.mapType(parts[1]) : { tsType: 'unknown', zodType: 'z.unknown()' };
        return {
          tsType: `Record<string, ${valueType.tsType}>`,
          formControl: 'textarea',
          inputType: 'text',
          zodType: `z.record(${valueType.zodType})`,
          defaultValue: '{}',
        };
      }

      if (container === 'Optional') {
        return {
          tsType: `${innerMapping.tsType} | null`,
          formControl: innerMapping.formControl,
          inputType: innerMapping.inputType,
          zodType: `${innerMapping.zodType}.nullable()`,
          defaultValue: 'null',
        };
      }
    }

    // Handle arrays like String[]
    if (javaType.endsWith('[]')) {
      const elementType = javaType.slice(0, -2);
      const elementMapping = this.mapType(elementType);
      return {
        tsType: DslMapper.arrayOf(elementMapping.tsType),
        formControl: 'textarea',
        inputType: 'text',
        zodType: `z.array(${elementMapping.zodType})`,
        defaultValue: '[]',
      };
    }

    // Fallback for unknown types (assume it's a custom entity/DTO)
    return {
      tsType: simpleName,
      formControl: 'input',
      inputType: 'text',
      zodType: `z.lazy(() => ${simpleName}Schema)`,
      defaultValue: 'null as unknown as ' + simpleName,
    };
  }

  /**
   * Map a field to complete form control metadata.
   */
  static mapField(field: FieldMetadata): TypeMapping & {
    fieldName: string;
    label: string;
    placeholder: string;
    validations: string[];
  } {
    const typeMapping = this.mapType(field.type);
    const validations: string[] = [];

    // Build Zod validations.
    //
    // Note on the asymmetric guards: minLength/maxLength use a TRUTHY
    // check (skips 0 + undefined), while min/max use `!== undefined`
    // (only skips undefined). Both are deliberate:
    //   - minLength=0 / maxLength=0 are semantically equivalent to
    //     omitting the constraint ("no minimum required" / "no maximum
    //     allowed"). Emitting `.min(0)` would be redundant noise on
    //     every form schema.
    //   - min=0 / max=0 are real numeric bounds ("value must be ≥ 0" /
    //     "value must be ≤ 0"). Skipping them would silently drop a
    //     user-supplied constraint.
    if (field.required) validations.push('.min(1)');
    if (field.minLength) validations.push(`.min(${field.minLength})`);
    if (field.maxLength) validations.push(`.max(${field.maxLength})`);
    if (field.min !== undefined) validations.push(`.min(${field.min})`);
    if (field.max !== undefined) validations.push(`.max(${field.max})`);
    if (field.pattern) validations.push(`.regex(/${field.pattern}/)`);
    if (field.format === 'email') validations.push('.email()');
    if (field.format === 'url') validations.push('.url()');

    // Determine HTML input type based on format
    let inputType = typeMapping.inputType;
    if (field.format) {
      switch (field.format) {
        case 'email':
          inputType = 'email';
          break;
        case 'url':
          inputType = 'url';
          break;
        case 'password':
          inputType = 'password';
          break;
        case 'tel':
          inputType = 'tel';
          break;
        case 'color':
          inputType = 'color';
          break;
      }
    }

    // Use textarea for long strings
    let formControl = typeMapping.formControl;
    if (typeMapping.formControl === 'input' && field.maxLength && field.maxLength > 255) {
      formControl = 'textarea';
    }

    return {
      ...typeMapping,
      formControl,
      inputType,
      fieldName: field.name,
      label: field.displayName ?? this.humanize(field.name),
      placeholder: field.description ?? '',
      validations,
    };
  }

  /**
   * Convert camelCase to human-readable label.
   */
  static humanize(name: string): string {
    return name
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (s) => s.toUpperCase())
      .trim();
  }

  /**
   * Convert entity name to kebab-case for file names.
   */
  static toKebabCase(name: string): string {
    return name
      .replace(/([a-z])([A-Z])/g, '$1-$2')
      .toLowerCase();
  }

  /**
   * Convert entity name to camelCase.
   */
  static toCamelCase(name: string): string {
    return name.charAt(0).toLowerCase() + name.slice(1);
  }

  /**
   * Generate TypeScript interface name from entity.
   */
  static toInterfaceName(entityName: string): string {
    return entityName.endsWith('Entity')
      ? entityName.slice(0, -6)
      : entityName;
  }
}

