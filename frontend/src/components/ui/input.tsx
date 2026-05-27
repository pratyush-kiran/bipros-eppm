import * as React from "react";
import { cn } from "@/lib/utils/cn";

export type InputProps = React.InputHTMLAttributes<HTMLInputElement> & {
  invalid?: boolean;
};

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, invalid, ...props }, ref) => (
    <input
      type={type}
      aria-invalid={invalid || undefined}
      ref={ref}
      className={cn(
        "flex h-10 w-full rounded-[10px] border bg-paper px-3.5 text-sm text-charcoal",
        "placeholder:text-ash",
        "transition-all duration-[120ms]",
        "hover:border-gold-deep/50",
        "focus-visible:outline-none focus-visible:border-gold focus-visible:shadow-[0_0_0_3px_rgba(0,88,202,0.18)]",
        "disabled:cursor-not-allowed disabled:bg-parchment disabled:text-ash",
        invalid
          ? "border-burgundy focus-visible:border-burgundy focus-visible:shadow-[0_0_0_3px_rgba(155,44,44,0.12)]"
          : "border-divider",
        className
      )}
      {...props}
    />
  )
);
Input.displayName = "Input";

export type FieldProps = React.HTMLAttributes<HTMLDivElement>;

export function Field({ className, ...props }: FieldProps) {
  return <div className={cn("flex flex-col gap-1.5", className)} {...props} />;
}

export type LabelProps = React.LabelHTMLAttributes<HTMLLabelElement>;

export function Label({ className, ...props }: LabelProps) {
  return (
    <label
      className={cn("text-xs font-semibold text-charcoal", className)}
      {...props}
    />
  );
}

export function FieldHint({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("text-xs text-slate", className)} {...props} />;
}

export function FieldError({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p
      className={cn("flex items-center gap-1 text-xs text-burgundy", className)}
      {...props}
    />
  );
}

export { Input };
