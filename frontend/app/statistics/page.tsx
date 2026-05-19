"use client";

import React from 'react';
import SidebarLayout from "@/components/layout/SidebarLayout";
import { 
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer,
  Line, Area, PieChart, Pie, Cell, ComposedChart
} from 'recharts';
import { TrendingUp, Users, Target, Award, Download, Filter } from 'lucide-react';

// Mock Data
const SCORE_DISTRIBUTION = [
  { range: '0-2', count: 2 },
  { range: '2-4', count: 5 },
  { range: '4-6', count: 12 },
  { range: '6-8', count: 18 },
  { range: '8-10', count: 8 },
];

const PROGRESS_TREND = [
  { day: 'T2', graded: 10, errors: 2 },
  { day: 'T3', graded: 25, errors: 4 },
  { day: 'T4', graded: 40, errors: 1 },
  { day: 'T5', graded: 70, errors: 5 },
  { day: 'T6', graded: 120, errors: 2 },
  { day: 'T7', graded: 150, errors: 0 },
  { day: 'CN', graded: 155, errors: 0 },
];

const PASS_FAIL_DATA = [
  { name: 'Pass (>=5)', value: 38, color: '#10b981' },
  { name: 'Fail (<5)', value: 7, color: '#f43f5e' },
];

export default function StatisticsPage() {
  return (
    <SidebarLayout title="Thống kê & Báo cáo (Analytics)" activePath="/statistics">
      <div className="space-y-6 max-w-7xl mx-auto pb-10">
        
        {/* Header Actions */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            <select className="bg-white border border-slate-200 text-slate-700 text-sm rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 shadow-sm font-medium">
              <option>Kỳ thi: Flutter Cơ bản (PE_06)</option>
              <option>Kỳ thi: ReactJS Nâng cao (PE_07)</option>
            </select>
            <button className="flex items-center gap-2 bg-white border border-slate-200 text-slate-600 px-3 py-2 rounded-lg text-sm font-medium hover:bg-slate-50 transition-colors shadow-sm">
              <Filter size={16} /> Lọc
            </button>
          </div>
          <button className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg text-sm font-semibold transition-all shadow-sm shadow-blue-600/20">
            <Download size={16} /> Xuất báo cáo PDF
          </button>
        </div>

        {/* Top KPIs */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex items-center gap-4">
            <div className="w-12 h-12 rounded-full bg-blue-50 flex items-center justify-center text-blue-600 shrink-0">
              <Users size={24} />
            </div>
            <div>
              <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-0.5">Tổng thí sinh</p>
              <p className="text-2xl font-bold text-slate-800">45</p>
            </div>
          </div>
          
          <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex items-center gap-4">
            <div className="w-12 h-12 rounded-full bg-emerald-50 flex items-center justify-center text-emerald-600 shrink-0">
              <Target size={24} />
            </div>
            <div>
              <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-0.5">Tỉ lệ Pass</p>
              <p className="text-2xl font-bold text-slate-800">84.4%</p>
            </div>
          </div>

          <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex items-center gap-4">
            <div className="w-12 h-12 rounded-full bg-amber-50 flex items-center justify-center text-amber-600 shrink-0">
              <Award size={24} />
            </div>
            <div>
              <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-0.5">Điểm trung bình</p>
              <p className="text-2xl font-bold text-slate-800">6.8 <span className="text-sm font-medium text-slate-400">/ 10</span></p>
            </div>
          </div>

          <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex items-center gap-4">
            <div className="w-12 h-12 rounded-full bg-indigo-50 flex items-center justify-center text-indigo-600 shrink-0">
              <TrendingUp size={24} />
            </div>
            <div>
              <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-0.5">Tiến độ chấm</p>
              <p className="text-2xl font-bold text-slate-800">100%</p>
            </div>
          </div>
        </div>

        {/* Charts Row 1 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Bar Chart: Phổ điểm */}
          <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 lg:col-span-2">
            <div className="mb-6">
              <h3 className="text-base font-bold text-slate-800">Phổ điểm của lớp (Score Distribution)</h3>
              <p className="text-sm text-slate-500">Phân bố điểm số của 45 sinh viên trong kỳ thi</p>
            </div>
            <div className="h-72 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={SCORE_DISTRIBUTION} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                  <XAxis dataKey="range" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                  <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                  <RechartsTooltip 
                    cursor={{ fill: '#f8fafc' }}
                    contentStyle={{ borderRadius: '12px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
                  />
                  <Bar dataKey="count" name="Số lượng SV" fill="#3b82f6" radius={[6, 6, 0, 0]} barSize={40} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Pie Chart: Pass/Fail Ratio */}
          <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col">
            <div className="mb-4">
              <h3 className="text-base font-bold text-slate-800">Tỉ lệ Đạt / Trượt</h3>
              <p className="text-sm text-slate-500">Mức điểm chuẩn Pass là 5.0</p>
            </div>
            <div className="flex-1 flex flex-col items-center justify-center relative">
              <ResponsiveContainer width="100%" height={220}>
                <PieChart>
                  <Pie
                    data={PASS_FAIL_DATA}
                    innerRadius={65}
                    outerRadius={90}
                    paddingAngle={5}
                    dataKey="value"
                    stroke="none"
                  >
                    {PASS_FAIL_DATA.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Pie>
                  <RechartsTooltip 
                    contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
                  />
                </PieChart>
              </ResponsiveContainer>
              {/* Center Text */}
              <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                <span className="text-3xl font-bold text-slate-800">84%</span>
                <span className="text-xs font-semibold text-emerald-500 uppercase tracking-wide">Pass</span>
              </div>
            </div>
            <div className="flex justify-center gap-6 mt-2">
              {PASS_FAIL_DATA.map((item) => (
                <div key={item.name} className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full" style={{ backgroundColor: item.color }}></div>
                  <span className="text-sm font-medium text-slate-600">{item.name} ({item.value})</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Charts Row 2 */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
          <div className="mb-6 flex justify-between items-end">
            <div>
              <h3 className="text-base font-bold text-slate-800">Tiến độ chấm bài trong tuần</h3>
              <p className="text-sm text-slate-500">Số lượng bài thi đã được xử lý bởi hệ thống Auto-grader</p>
            </div>
          </div>
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={PROGRESS_TREND} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                <defs>
                  <linearGradient id="colorGraded" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.2}/>
                    <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                <XAxis dataKey="day" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                <RechartsTooltip 
                  contentStyle={{ borderRadius: '12px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
                />
                <Area type="monotone" dataKey="graded" name="Số bài hoàn thành" stroke="#8b5cf6" strokeWidth={3} fillOpacity={1} fill="url(#colorGraded)" />
                <Line type="monotone" dataKey="errors" name="Lỗi hệ thống" stroke="#f43f5e" strokeWidth={2} dot={{ r: 4, fill: '#f43f5e' }} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </div>

      </div>
    </SidebarLayout>
  );
}
