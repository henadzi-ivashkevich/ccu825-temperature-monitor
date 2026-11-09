const SHEET_NAME = 'мониторинг';
const API_TOKEN_PROPERTY = 'API_TOKEN';
const SPREADSHEET_ID_PROPERTY = 'SPREADSHEET_ID';

const INPUT_HEADERS = [
  'Объем', 'Дымовой 2эт', 'Люк УГБО', 'Улица °C', 'Протечка', '2 этаж °C',
  'Камин тепловой', 'Уровень УГБО', 'Гараж ворота', 'Гараж вибро', 'Гараж дым',
  '1 этаж °C', 'Вход 13', 'Вход 14', 'Вход 15', 'Вход 16'
];

const EXTRA_HEADERS = [
  'Выход 1', 'Выход 2', 'Выход 3', 'Выход 4', 'Выход 5', 'Выход 6', 'Выход 7',
  'Заряд батареи, %', 'Состояние батареи', 'Power', 'Температура платы, °C'
];

const ALL_HEADERS = ['Время', ...INPUT_HEADERS, ...EXTRA_HEADERS];

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const expectedToken = PropertiesService.getScriptProperties().getProperty(API_TOKEN_PROPERTY);
    if (!expectedToken) {
      return jsonOutput_({ok: false, error: 'Server token is not configured'});
    }
    if (data.token !== expectedToken) {
      return jsonOutput_({ok: false, error: 'Unauthorized'});
    }

    const ccu = data.payload;
    if (!ccu || !Array.isArray(ccu.Inputs) || ccu.Inputs.length !== 16) {
      return jsonOutput_({
        ok: false,
        error: 'Invalid CCU data',
        payloadPreview: JSON.stringify(ccu).substring(0, 1000)
      });
    }
    if (!Array.isArray(ccu.Outputs) || ccu.Outputs.length !== 7 ||
        !ccu.Battery || typeof ccu.Battery !== 'object' ||
        !Number.isFinite(Number(ccu.Power)) || !Number.isFinite(Number(ccu.Temp))) {
      return jsonOutput_({ok: false, error: 'Missing or invalid CCU Outputs, Battery, Power or Temp'});
    }

    // Input voltage conversion is unchanged from the deployed script.
    const inputValues = ccu.Inputs.map((input, i) => {
      const inputNo = i + 1;
      if ([4, 6, 12].includes(inputNo)) {
        const adc = Number(input.Voltage);
        const voltage = adc * 10 / 4095;
        const temperature = (voltage / 5 - 0.5) / 0.01;
        return Number(temperature.toFixed(2));
      }
      return Number(input.Active);
    });

    const spreadsheet = openSpreadsheet_();
    let sheet = spreadsheet.getSheetByName(SHEET_NAME);
    if (!sheet) sheet = spreadsheet.insertSheet(SHEET_NAME);
    const headers = ensureHeaders_(sheet);

    const rowByHeader = {'Время': new Date()};
    INPUT_HEADERS.forEach((header, index) => { rowByHeader[header] = inputValues[index]; });
    ccu.Outputs.forEach((value, index) => { rowByHeader['Выход ' + (index + 1)] = value; });
    rowByHeader['Заряд батареи, %'] = cellValue_(ccu.Battery.Charge);
    rowByHeader['Состояние батареи'] = cellValue_(ccu.Battery.State);
    rowByHeader.Power = cellValue_(ccu.Power);
    rowByHeader['Температура платы, °C'] = cellValue_(ccu.Temp);

    const row = headers.map(header => Object.prototype.hasOwnProperty.call(rowByHeader, header)
      ? rowByHeader[header]
      : '');
    sheet.appendRow(row);
    return jsonOutput_({
      ok: true,
      inputCount: 16,
      outputCount: 7,
      sheet: SHEET_NAME,
      lastRow: sheet.getLastRow()
    });
  } catch (err) {
    return jsonOutput_({ok: false, error: String(err)});
  }
}

function ensureHeaders_(sheet) {
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(ALL_HEADERS);
    return ALL_HEADERS;
  }

  const lastColumn = sheet.getLastColumn();
  const existing = sheet.getRange(1, 1, 1, lastColumn).getDisplayValues()[0];
  const missing = ALL_HEADERS.filter(header => existing.indexOf(header) < 0);
  if (missing.length > 0) {
    sheet.getRange(1, lastColumn + 1, 1, missing.length).setValues([missing]);
  }
  return existing.concat(missing);
}

function cellValue_(value) {
  return value === undefined || value === null ? '' : value;
}

function openSpreadsheet_() {
  const spreadsheetId = PropertiesService.getScriptProperties().getProperty(SPREADSHEET_ID_PROPERTY);
  if (!spreadsheetId) throw new Error('Spreadsheet ID is not configured');
  return SpreadsheetApp.openById(spreadsheetId);
}

function doGet(e) {
  if (e && e.parameter && e.parameter.action === 'history') {
    return getTemperatureHistory_(e);
  }
  return jsonOutput_({ok: false, error: 'Use action=history'});
}

function getTemperatureHistory_(e) {
  try {
    const from = e.parameter.from;
    const to = e.parameter.to;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from || '') ||
        !/^\d{4}-\d{2}-\d{2}$/.test(to || '') || from > to) {
      throw new Error('Expected from/to as YYYY-MM-DD');
    }

    const spreadsheet = openSpreadsheet_();
    const sheet = spreadsheet.getSheetByName(SHEET_NAME);
    if (!sheet || sheet.getLastRow() < 2) return jsonOutput_({ok: true, readings: []});

    const rows = sheet.getDataRange().getValues();
    const headers = rows[0].map(String);
    const column = name => {
      const index = headers.indexOf(name);
      if (index < 0) throw new Error('Missing sheet column: ' + name);
      return index;
    };
    const timeCol = column('Время');
    const inputColumns = INPUT_HEADERS.map(column);
    const timezone = spreadsheet.getSpreadsheetTimeZone();
    const readings = [];

    for (let i = 1; i < rows.length; i++) {
      const row = rows[i];
      const rawTime = row[timeCol];
      const date = rawTime instanceof Date ? rawTime : new Date(rawTime);
      if (isNaN(date.getTime())) continue;
      const day = Utilities.formatDate(date, timezone, 'yyyy-MM-dd');
      if (day < from || day > to) continue;

      readings.push({
        time: date.getTime(),
        inputs: inputColumns.map(index => numberOrNull_(row[index])),
        // Preserve compatibility with older Android versions.
        outside: numberOrNull_(row[inputColumns[3]]),
        floor2: numberOrNull_(row[inputColumns[5]]),
        floor1: numberOrNull_(row[inputColumns[11]])
      });
    }

    readings.sort((a, b) => a.time - b.time);
    return jsonOutput_({ok: true, readings: readings});
  } catch (error) {
    return jsonOutput_({ok: false, error: String(error.message || error)});
  }
}

function numberOrNull_(value) {
  if (value === '' || value === null || value === undefined) return null;
  if (typeof value === 'boolean') return value ? 1 : 0;
  const text = String(value).trim();
  if (/^(true|on)$/i.test(text)) return 1;
  if (/^(false|off)$/i.test(text)) return 0;
  const number = typeof value === 'number' ? value : Number(text.replace(',', '.'));
  return Number.isFinite(number) ? number : null;
}

function jsonOutput_(value) {
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
