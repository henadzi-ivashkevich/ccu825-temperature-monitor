// Add these helpers to the existing Apps Script that writes CCU825 readings.
// ccuResponse may be the parsed response object or Apps Script HTTPResponse.

const CCU_EXTRA_HEADERS_ = [
  'Ворота', 'Электричество', 'СЗУ', 'Гараж', 'УГБО', 'Оборгеватель', 'Выход 7',
  'Заряд батареи, %', 'Состояние батареи', 'Power', 'Температура платы, °C'
];

/** Adds the extra columns to row 1 once, without moving existing columns. */
function ensureCcuExtraHeaders_(sheet) {
  if (sheet.getLastRow() === 0) {
    throw new Error('Create the existing base header row before adding CCU extra headers.');
  }

  const lastColumn = sheet.getLastColumn();
  const currentHeaders = sheet.getRange(1, 1, 1, lastColumn).getDisplayValues()[0];
  const missingHeaders = CCU_EXTRA_HEADERS_.filter(header => currentHeaders.indexOf(header) < 0);
  if (missingHeaders.length > 0) {
    sheet.getRange(1, lastColumn + 1, 1, missingHeaders.length).setValues([missingHeaders]);
  }
}

/** Appends one measurement row while preserving the existing base row layout. */
function appendCcuMeasurementRow_(sheet, baseRowValues, ccuResponse) {
  const ccu = parseCcuResponse_(ccuResponse);

  ensureCcuExtraHeaders_(sheet);

  const outputs = Array.isArray(ccu.Outputs) ? ccu.Outputs : [];
  const battery = ccu.Battery && typeof ccu.Battery === 'object'
    ? ccu.Battery
    : {};
  const extraValues = {};
  for (let i = 0; i < 7; i++) {
    extraValues['Выход ' + (i + 1)] = outputs[i] === undefined || outputs[i] === null ? '' : outputs[i];
  }
  extraValues['Заряд батареи, %'] = battery.Charge === undefined || battery.Charge === null ? '' : battery.Charge;
  extraValues['Состояние батареи'] = battery.State === undefined || battery.State === null ? '' : battery.State;
  extraValues.Power = ccu.Power === undefined || ccu.Power === null ? '' : ccu.Power;
  extraValues['Температура платы, °C'] = ccu.Temp === undefined || ccu.Temp === null ? '' : ccu.Temp;

  const lastColumn = sheet.getLastColumn();
  const headers = sheet.getRange(1, 1, 1, lastColumn).getDisplayValues()[0];
  const row = Array(lastColumn).fill('');
  baseRowValues.forEach((value, index) => {
    if (index < row.length) row[index] = value;
  });
  headers.forEach((header, index) => {
    if (Object.prototype.hasOwnProperty.call(extraValues, header)) row[index] = extraValues[header];
  });

  sheet.appendRow(row);
}

function parseCcuResponse_(response) {
  if (typeof response === 'string') return JSON.parse(response);
  if (response && typeof response.getContentText === 'function') {
    return JSON.parse(response.getContentText());
  }
  if (response && typeof response === 'object') return response;
  throw new Error('ccuResponse is empty or is not a CCU response.');
}

/*
Integration point in the existing writer:

  // Previously:
  // sheet.appendRow(baseRowValues);

  // Now:
  appendCcuMeasurementRow_(sheet, baseRowValues, ccuResponse);

Keep the existing doPost(e), first CCU request, and baseRowValues construction.
Call this helper instead of the existing appendRow so the sensor fields and
new CCU values are written together on the same row.
*/
