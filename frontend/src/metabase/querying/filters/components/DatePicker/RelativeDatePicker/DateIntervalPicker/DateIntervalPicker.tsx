import type { FormEvent, ReactNode } from "react";
import { t } from "ttag";

import type {
  DatePickerUnit,
  RelativeDatePickerValue,
} from "metabase/querying/filters/types";
import {
  Button,
  Divider,
  Flex,
  Group,
  Icon,
  NumberInput,
  Select,
  Text,
  Tooltip,
} from "metabase/ui";

import type { DatePickerSubmitButtonProps } from "../../types";
import { renderDefaultSubmitButton } from "../../utils";
import { IncludeCurrentSwitch } from "../IncludeCurrentSwitch";
import {
  formatDateRange,
  getInterval,
  getUnitOptions,
  setInterval,
} from "../utils";

import { setDefaultOffset, setUnit } from "./utils";

interface DateIntervalPickerProps {
  value: RelativeDatePickerValue;
  availableUnits: DatePickerUnit[];
  renderSubmitButton?: (props: DatePickerSubmitButtonProps) => ReactNode;
  onChange: (value: RelativeDatePickerValue) => void;
  onSubmit: () => void;
}

export function DateIntervalPicker({
  value,
  availableUnits,
  renderSubmitButton = renderDefaultSubmitButton,
  onChange,
  onSubmit,
}: DateIntervalPickerProps) {
  const interval = getInterval(value);
  const unitOptions = getUnitOptions(value, availableUnits);
  const dateRangeText = formatDateRange(value);

  const handleIntervalChange = (inputValue: number | "") => {
    if (inputValue !== "") {
      onChange(setInterval(value, inputValue));
    }
  };

  const handleUnitChange = (inputValue: string | null) => {
    const option = unitOptions.find((option) => option.value === inputValue);
    if (option) {
      onChange(setUnit(value, option.value));
    }
  };

  const handleStartingFromClick = () => {
    onChange(setDefaultOffset(value));
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <form onSubmit={handleSubmit}>
      <Flex p="md" align="center">
        <NumberInput
          value={interval}
          aria-label={t`Interval`}
          w="4rem"
          onChange={handleIntervalChange}
        />
        <Select
          data={unitOptions}
          value={value.unit}
          aria-label={t`Unit`}
          ml="md"
          onChange={handleUnitChange}
          comboboxProps={{
            withinPortal: false,
            floatingStrategy: "fixed",
          }}
        />
        <Tooltip label={t`Starting from…`} position="bottom">
          <Button
            aria-label={t`Starting from…`}
            c="var(--mb-color-text-secondary)"
            variant="subtle"
            leftSection={<Icon name="arrow_left_to_line" />}
            onClick={handleStartingFromClick}
          />
        </Tooltip>
      </Flex>
      <Flex p="md" pt={0}>
        <IncludeCurrentSwitch value={value} onChange={onChange} />
      </Flex>
      <Divider />
      <Group px="md" py="sm" justify="space-between">
        <Group c="var(--mb-color-text-secondary)" gap="sm">
          <Icon name="calendar" />
          <Text c="inherit">{dateRangeText}</Text>
        </Group>
        {renderSubmitButton({ value, isDisabled: false })}
      </Group>
    </form>
  );
}
