export function parseArgs(argv: string[]): Map<string, string[]> {
  const result = new Map<string, string[]>();
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token?.startsWith("--")) throw new Error(`无法识别参数：${token}`);
    const key = token.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`参数 --${key} 缺少值`);
    result.set(key, [...(result.get(key) ?? []), value]);
    index += 1;
  }
  return result;
}

export function requiredArg(args: Map<string, string[]>, name: string): string {
  const value = args.get(name)?.at(-1);
  if (!value) throw new Error(`缺少参数 --${name}`);
  return value;
}

export function optionalArg(args: Map<string, string[]>, name: string): string | undefined {
  return args.get(name)?.at(-1);
}

export function allArgs(args: Map<string, string[]>, name: string): string[] {
  return args.get(name) ?? [];
}

export function failCli(error: unknown): never {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(`失败：${message}\n`);
  process.exit(1);
}
